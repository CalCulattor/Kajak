package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// Konta użytkowników: sama nazwa i hasło (bez e-maila). Hasła są przechowywane wyłącznie
// jako PBKDF2-HMAC-SHA256 z losową solą, a tokeny sesji jako skrót SHA-256 (wyciek pliku
// danych nie ujawnia ani haseł, ani działających tokenów).

var usernameRe = regexp.MustCompile(`^[A-Za-z0-9_.-]{3,24}$`)

const (
	minPasswordRunes = 8
	maxPasswordRunes = 128
	sessionTTL       = 90 * 24 * time.Hour
)

// pbkdf2Iterations to koszt hasha (zalecenie OWASP dla PBKDF2-SHA256: 600 000).
// Zmienna, żeby testy mogły ją obniżyć.
var pbkdf2Iterations = 600_000

var errUsernameTaken = errors.New("nazwa użytkownika jest zajęta")

type User struct {
	ID         int64     `json:"id"`
	Username   string    `json:"username"`
	Salt       string    `json:"salt"`
	Hash       string    `json:"hash"`
	Iterations int       `json:"iterations"`
	CreatedAt  time.Time `json:"created_at"`
}

type Session struct {
	TokenHash string    `json:"token_hash"`
	Username  string    `json:"username"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

// pbkdf2SHA256 to PBKDF2 (RFC 8018) z HMAC-SHA256; biblioteka standardowa Go 1.22 go nie ma.
func pbkdf2SHA256(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hLen := prf.Size()
	blocks := (keyLen + hLen - 1) / hLen
	dk := make([]byte, 0, blocks*hLen)
	u := make([]byte, 0, hLen)
	var counter [4]byte
	for block := 1; block <= blocks; block++ {
		prf.Reset()
		prf.Write(salt)
		counter[0], counter[1], counter[2], counter[3] = byte(block>>24), byte(block>>16), byte(block>>8), byte(block)
		prf.Write(counter[:])
		dk = prf.Sum(dk)
		t := dk[len(dk)-hLen:]
		u = append(u[:0], t...)
		for n := 2; n <= iter; n++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for i := range t {
				t[i] ^= u[i]
			}
		}
	}
	return dk[:keyLen]
}

func hashPassword(password string, salt []byte, iter int) string {
	return hex.EncodeToString(pbkdf2SHA256([]byte(password), salt, iter, 32))
}

func checkPassword(u User, password string) bool {
	salt, err := hex.DecodeString(u.Salt)
	if err != nil {
		return false
	}
	got := hashPassword(password, salt, u.Iterations)
	return subtle.ConstantTimeCompare([]byte(got), []byte(u.Hash)) == 1
}

func newToken() (token, tokenHash string, err error) {
	raw := make([]byte, 32)
	if _, err = rand.Read(raw); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(raw)
	return token, hashToken(token), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// ---------------------------------------------------------------- magazyn

func (st *Store) CreateUser(username string, salt []byte, hash string, iter int) (User, error) {
	var out User
	err := st.mutate(func(s *state) error {
		for _, u := range s.Users {
			if strings.EqualFold(u.Username, username) {
				return errUsernameTaken
			}
		}
		out = User{
			ID: st.nextIDIn(s), Username: username, Salt: hex.EncodeToString(salt),
			Hash: hash, Iterations: iter, CreatedAt: st.now(),
		}
		s.Users = append(s.Users, out)
		return nil
	})
	return out, err
}

func (st *Store) FindUser(username string) (User, bool) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	for _, u := range st.s.Users {
		if strings.EqualFold(u.Username, username) {
			return u, true
		}
	}
	return User{}, false
}

// CreateSession zapisuje sesję i przy okazji usuwa wygasłe.
func (st *Store) CreateSession(tokenHash, username string) error {
	return st.mutate(func(s *state) error {
		now := st.now()
		kept := s.Sessions[:0:0]
		for _, e := range s.Sessions {
			if e.ExpiresAt.After(now) {
				kept = append(kept, e)
			}
		}
		s.Sessions = append(kept, Session{
			TokenHash: tokenHash, Username: username, CreatedAt: now, ExpiresAt: now.Add(sessionTTL),
		})
		return nil
	})
}

func (st *Store) UsernameForToken(tokenHash string) (string, bool) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	now := st.now()
	for _, e := range st.s.Sessions {
		if subtle.ConstantTimeCompare([]byte(e.TokenHash), []byte(tokenHash)) == 1 && e.ExpiresAt.After(now) {
			return e.Username, true
		}
	}
	return "", false
}

func (st *Store) DeleteSession(tokenHash string) error {
	return st.mutate(func(s *state) error {
		s.Sessions = filterSlice(s.Sessions, func(e Session) bool { return e.TokenHash != tokenHash })
		return nil
	})
}

// ---------------------------------------------------------------- ograniczanie prób

// limiter ogranicza liczbę zdarzeń w oknie czasowym (w pamięci, per klucz).
type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	hits   map[string][]time.Time
	now    func() time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, hits: map[string][]time.Time{}, now: time.Now}
}

func (l *limiter) recent(key string) []time.Time {
	cutoff := l.now().Add(-l.window)
	list := l.hits[key]
	kept := list[:0]
	for _, t := range list {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) == 0 {
		delete(l.hits, key)
	} else {
		l.hits[key] = kept
	}
	return kept
}

// Blocked mówi, czy klucz przekroczył limit (nie zlicza).
func (l *limiter) Blocked(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.recent(key)) >= l.max
}

func (l *limiter) Add(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.recent(key)
	l.hits[key] = append(l.hits[key], l.now())
}

func (l *limiter) Reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.hits, key)
}

// ---------------------------------------------------------------- HTTP

type credentials struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type authResponse struct {
	Token    string `json:"token"`
	Username string `json:"username"`
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):])
	}
	return ""
}

// currentUser zwraca zalogowanego użytkownika albo odpowiada 401.
func (srv *Server) currentUser(w http.ResponseWriter, r *http.Request) (string, bool) {
	token := bearerToken(r)
	if token != "" {
		if name, ok := srv.store.UsernameForToken(hashToken(token)); ok {
			return name, true
		}
	}
	w.Header().Set("WWW-Authenticate", "Bearer")
	writeError(w, http.StatusUnauthorized, "zaloguj się")
	return "", false
}

func (srv *Server) register(w http.ResponseWriter, r *http.Request) {
	var req credentials
	if !decode(w, r, &req) {
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	if !usernameRe.MatchString(req.Username) {
		writeError(w, http.StatusBadRequest, "nazwa użytkownika: 3-24 znaków, tylko litery a-z, cyfry oraz _ . -")
		return
	}
	if n := utf8.RuneCountInString(req.Password); n < minPasswordRunes || n > maxPasswordRunes {
		writeError(w, http.StatusBadRequest, "hasło musi mieć od 8 do 128 znaków")
		return
	}
	if srv.registerLimit.Blocked("all") {
		writeError(w, http.StatusTooManyRequests, "zbyt wiele rejestracji, spróbuj później")
		return
	}
	if _, exists := srv.store.FindUser(req.Username); exists {
		writeError(w, http.StatusConflict, "nazwa użytkownika jest zajęta")
		return
	}

	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		writeError(w, http.StatusInternalServerError, "błąd wewnętrzny serwera")
		return
	}
	hash := hashPassword(req.Password, salt, pbkdf2Iterations)
	user, err := srv.store.CreateUser(req.Username, salt, hash, pbkdf2Iterations)
	if errors.Is(err, errUsernameTaken) {
		writeError(w, http.StatusConflict, "nazwa użytkownika jest zajęta")
		return
	}
	if err != nil {
		storeError(w, err)
		return
	}
	srv.registerLimit.Add("all")
	srv.startSession(w, user.Username, http.StatusCreated)
}

func (srv *Server) login(w http.ResponseWriter, r *http.Request) {
	var req credentials
	if !decode(w, r, &req) {
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	key := strings.ToLower(req.Username)
	if srv.loginLimit.Blocked(key) {
		writeError(w, http.StatusTooManyRequests, "zbyt wiele nieudanych prób logowania, spróbuj za kilka minut")
		return
	}

	user, found := srv.store.FindUser(req.Username)
	ok := false
	if found {
		ok = checkPassword(user, req.Password)
	} else {
		// Podobny koszt czasowy jak dla istniejącego konta (utrudnia zgadywanie nazw).
		hashPassword(req.Password, make([]byte, 16), pbkdf2Iterations)
	}
	if !ok {
		srv.loginLimit.Add(key)
		writeError(w, http.StatusUnauthorized, "niepoprawna nazwa użytkownika lub hasło")
		return
	}
	srv.loginLimit.Reset(key)
	srv.startSession(w, user.Username, http.StatusOK)
}

func (srv *Server) startSession(w http.ResponseWriter, username string, status int) {
	token, tokenHash, err := newToken()
	if err == nil {
		err = srv.store.CreateSession(tokenHash, username)
	}
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, status, authResponse{Token: token, Username: username})
}

func (srv *Server) logout(w http.ResponseWriter, r *http.Request) {
	if _, ok := srv.currentUser(w, r); !ok {
		return
	}
	if err := srv.store.DeleteSession(hashToken(bearerToken(r))); err != nil {
		storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (srv *Server) me(w http.ResponseWriter, r *http.Request) {
	name, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"username": name})
}
