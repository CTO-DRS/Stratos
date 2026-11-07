package engine

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// SiteConfig is the runtime configuration of one hosted site.
type SiteConfig struct {
	ID       string
	Name     string
	Root     string // absolute path of the site root on disk
	Default  bool   // served at "/"
	Enabled  bool   // disabled sites answer 404
	SPA      bool   // unknown paths fall back to /index.html
	CacheMax int    // Cache-Control max-age for hashed assets; 0 = sensible default
}

// site is the internal, immutable view of a SiteConfig plus its os.Root.
type site struct {
	cfg  *SiteConfig
	root *os.Root // confinement: every open goes through this handle
}

// Server hosts multiple static sites inside this process.
type Server struct {
	mu       sync.RWMutex
	sites    map[string]*site
	defID    string
	listener net.Listener
	http     *http.Server
	addr     string
	started  time.Time
	metrics  *Metrics

	// Whole-server HTTP Basic access protection (empty user = disabled).
	authUser string
	authPass string
}

// maxCacheAgeDefault is applied when a site does not set CacheMax.
const maxCacheAgeDefault = 3600

// NewServer validates site roots and prepares (but does not start) a server.
// bindHost may be "", "0.0.0.0" (all interfaces), "127.0.0.1" (loopback) or a
// specific LAN IP chosen by the Kotlin layer.
func NewServer(bindHost string, port int, configs []*SiteConfig) (*Server, error) {
	// port 0 = ephemeral (auto-assign) — used by tests and the "auto" option.
	if port < 0 || port > 65535 {
		return nil, fmt.Errorf("port out of range: %d", port)
	}
	if bindHost == "" {
		bindHost = "0.0.0.0"
	}
	s := &Server{
		sites:   make(map[string]*site, len(configs)),
		metrics: NewMetrics(),
	}
	var defCount int
	for _, c := range configs {
		if !c.Enabled {
			continue
		}
		abs, err := filepath.Abs(c.Root)
		if err != nil {
			return nil, fmt.Errorf("site %s: bad root: %v", c.ID, err)
		}
		st, err := os.Stat(abs)
		if err != nil || !st.IsDir() {
			return nil, fmt.Errorf("site %s: root is not a directory: %s", c.ID, abs)
		}
		// Open an os.Root handle: from here on, all file opens for this site
		// are confined to its root (symlinks that escape are refused).
		root, err := os.OpenRoot(abs)
		if err != nil {
			return nil, fmt.Errorf("site %s: cannot open root: %v", c.ID, err)
		}
		s.sites[c.ID] = &site{cfg: c, root: root}
		if c.Default {
			defCount++
			s.defID = c.ID
		}
	}
	if defCount > 1 {
		s.closeRoots()
		return nil, fmt.Errorf("more than one site marked default")
	}
	if len(s.sites) == 0 {
		s.closeRoots()
		return nil, fmt.Errorf("no enabled sites")
	}
	addr := net.JoinHostPort(bindHost, fmt.Sprint(port))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		s.closeRoots()
		return nil, fmt.Errorf("listen %s: %v", addr, err)
	}
	s.listener = ln
	s.addr = ln.Addr().String()
	return s, nil
}

// Start begins serving in a background goroutine.
func (s *Server) Start() error {
	s.mu.Lock()
	if s.http != nil {
		s.mu.Unlock()
		return errors.New("already started")
	}
	s.started = time.Now()
	s.http = &http.Server{
		Handler:           s,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	srv := s.http
	ln := s.listener
	s.mu.Unlock()

	appendLog("INFO", "engine serving on "+s.Addr())
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			appendLog("ERROR", "serve: "+err.Error())
		}
	}()
	return nil
}

// Stop gracefully shuts down with a short deadline and closes os.Roots.
func (s *Server) Stop() error {
	s.mu.Lock()
	h := s.http
	s.http = nil
	s.mu.Unlock()
	if h == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = h.Shutdown(ctx)
	s.closeRoots()
	appendLog("INFO", "engine stopped")
	return nil
}

// Addr returns the listening address (host:port).
func (s *Server) Addr() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.listener == nil {
		return ""
	}
	return s.listener.Addr().String()
}

func (s *Server) closeRoots() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, st := range s.sites {
		_ = st.root.Close()
	}
	s.sites = map[string]*site{}
}

// SetSites hot-reloads the site registry without dropping the listener.
func (s *Server) SetSites(configs []*SiteConfig) {
	newSites := make(map[string]*site, len(configs))
	defID := ""
	for _, c := range configs {
		if !c.Enabled {
			continue
		}
		abs, err := filepath.Abs(c.Root)
		if err != nil {
			continue
		}
		if st, err := os.Stat(abs); err != nil || !st.IsDir() {
			continue
		}
		root, err := os.OpenRoot(abs)
		if err != nil {
			continue
		}
		newSites[c.ID] = &site{cfg: c, root: root}
		if c.Default {
			defID = c.ID
		}
	}
	s.mu.Lock()
	old := s.sites
	s.sites = newSites
	s.defID = defID
	s.mu.Unlock()
	for _, st := range old {
		_ = st.root.Close()
	}
	appendLog("INFO", fmt.Sprintf("registry reloaded: %d site(s)", len(newSites)))
}

// Snapshot returns engine state for the status command.
func (s *Server) Snapshot() map[string]any {
	s.mu.RLock()
	running := s.http != nil
	addr := s.addr
	if s.listener != nil {
		addr = s.listener.Addr().String()
	}
	var up float64
	if running {
		up = time.Since(s.started).Seconds()
	}
	def := s.defID
	n := len(s.sites)
	s.mu.RUnlock()
	out := s.metrics.Snapshot()
	out["running"] = running
	out["addr"] = addr
	out["uptimeSec"] = up
	out["defaultSite"] = def
	out["sites"] = n
	return out
}

// ResetMetrics zeroes request counters (device metrics are Kotlin-side).
func (s *Server) ResetMetrics() { s.metrics.Reset() }

// SetAuth hot-applies whole-server HTTP Basic access protection. An empty
// user disables the wall; comparisons are constant-time.
func (s *Server) SetAuth(user, pass string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.authUser, s.authPass = user, pass
}

// ---- HTTP routing -------------------------------------------------------

// ServeHTTP implements the routing layer:
//
//	/            → default site
//	/s/<id>/...  → secondary site by ID
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		s.metrics.ActiveOpen()
		defer s.metrics.ActiveClose()
		w.Header().Set("Allow", "GET, HEAD")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusMethodNotAllowed)
		if r.Method != http.MethodHead {
			_, _ = w.Write([]byte(errorPage(http.StatusMethodNotAllowed, "405 — Method Not Allowed",
				"This server speaks GET and HEAD only.")))
		}
		s.metrics.Record(http.StatusMethodNotAllowed, 0, 0)
		return
	}

	// Access protection: every request sits behind the credential wall
	// whenever one is configured.
	if !s.checkAuth(w, r) {
		return
	}

	urlPath := r.URL.Path // net/http already decodes %XX
	s.metrics.NotePath(urlPath)
	var target *site
	var rel string

	if id, rest, ok := splitSecondary(urlPath); ok {
		s.mu.RLock()
		target = s.sites[id]
		s.mu.RUnlock()
		if target == nil {
			s.metrics.ActiveOpen()
			s.notFound(w, r, "site")
			s.metrics.ActiveClose()
			return
		}
		rel = rest
	} else {
		s.mu.RLock()
		id := s.defID
		target = s.sites[id]
		s.mu.RUnlock()
		if target == nil {
			s.metrics.ActiveOpen()
			s.notFound(w, r, "default")
			s.metrics.ActiveClose()
			return
		}
		rel = urlPath
	}

	s.serveSite(w, r, target, rel)
}

// splitSecondary parses "/s/<id>/rest" and reports whether the URL targets a
// secondary site. Malformed secondary prefixes fall through to the default
// site rather than leaking errors.
func splitSecondary(p string) (id, rest string, ok bool) {
	const prefix = "/s/"
	if !strings.HasPrefix(p, prefix) {
		return "", "", false
	}
	rem := p[len(prefix):]
	if rem == "" {
		return "", "", false
	}
	slash := strings.IndexByte(rem, '/')
	if slash < 0 {
		return rem, "/", true
	}
	return rem[:slash], rem[slash:], true
}

func (s *Server) notFound(w http.ResponseWriter, r *http.Request, kind string) {
	// No default site configured at all → friendly branded welcome page.
	if kind == "default" && s.defaultID() == "" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write([]byte(welcomePage(s.Addr())))
		}
		s.metrics.Record(http.StatusOK, 0, 0)
		return
	}
	msg := "The page you requested does not exist on this server."
	if kind == "site" {
		msg = "This site is not hosted (disabled or removed)."
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusNotFound)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(errorPage(http.StatusNotFound, "404 — Not Found", msg)))
	}
	s.metrics.Record(http.StatusNotFound, 0, 0)
}

// checkAuth enforces the optional Basic-auth wall. With no user configured
// it is a no-op. Failures answer 401 with a branded page and a challenge.
func (s *Server) checkAuth(w http.ResponseWriter, r *http.Request) bool {
	s.mu.RLock()
	user, pass := s.authUser, s.authPass
	s.mu.RUnlock()
	if user == "" {
		return true
	}
	if u, p, ok := r.BasicAuth(); ok &&
		subtle.ConstantTimeCompare([]byte(u), []byte(user)) == 1 &&
		subtle.ConstantTimeCompare([]byte(p), []byte(pass)) == 1 {
		return true
	}
	w.Header().Set("WWW-Authenticate", `Basic realm="Stratos", charset="UTF-8"`)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusUnauthorized)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(errorPage(http.StatusUnauthorized, "401 — Unauthorized",
			"This server is protected. Enter the username and password set in Stratos.")))
	}
	s.metrics.Record(http.StatusUnauthorized, 0, 0)
	return false
}

// defID returns the current default site id (or "").
func (s *Server) defaultID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.defID
}
