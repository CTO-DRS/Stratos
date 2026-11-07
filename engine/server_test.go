package engine

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newTestServer builds a temp site tree, starts a real server on a random
// loopback port and returns its base URL plus a cleanup function.
func newTestServer(t *testing.T, spa bool) (base string, root string, cleanup func()) {
	t.Helper()
	dir := t.TempDir()
	main := filepath.Join(dir, "main")
	if err := os.MkdirAll(filepath.Join(main, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	write := func(p, c string) {
		if err := os.WriteFile(filepath.Join(p), []byte(c), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(main, "index.html"), "<h1>Main Home</h1>")
	write(filepath.Join(main, "about.html"), "<h1>Main About</h1>")
	write(filepath.Join(main, "assets", "style.css"), "body{color:#0a0a0a}")
	write(filepath.Join(main, "assets", "big.txt"), strings.Repeat("A", 4096))

	second := filepath.Join(dir, "second")
	if err := os.MkdirAll(second, 0o755); err != nil {
		t.Fatal(err)
	}
	write(filepath.Join(second, "index.html"), "<h1>Second Home</h1>")

	srv, err := NewServer("127.0.0.1", 0, []*SiteConfig{
		{ID: "main", Root: main, Default: true, Enabled: true, SPA: spa},
		{ID: "sec", Root: second, Enabled: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	engineMu.Lock()
	activeServer = srv
	engineMu.Unlock()
	base = "http://" + srv.Addr()
	return base, main, func() {
		_ = srv.Stop()
		engineMu.Lock()
		activeServer = nil
		engineMu.Unlock()
	}
}

func get(t *testing.T, url string, headers map[string]string) (*http.Response, string) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer resp.Body.Close()
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, resp.Body)
	return resp, buf.String()
}

func TestServeIndex(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	resp, body := get(t, base+"/", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	if !strings.Contains(body, "Main Home") {
		t.Fatalf("unexpected body: %q", body)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Fatalf("bad content type: %q", ct)
	}
	if resp.Header.Get("ETag") == "" {
		t.Fatal("missing ETag")
	}
}

func TestSecondarySite(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	resp, body := get(t, base+"/s/sec/", nil)
	if resp.StatusCode != 200 || !strings.Contains(body, "Second Home") {
		t.Fatalf("secondary failed: %d %q", resp.StatusCode, body)
	}
	// Unknown secondary id → branded 404, no leak.
	resp, _ = get(t, base+"/s/nope/", nil)
	if resp.StatusCode != 404 {
		t.Fatalf("want 404, got %d", resp.StatusCode)
	}
}

func TestTraversalRejected(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	for _, p := range []string{"/../etc/passwd", "/s/sec/../../secret", "/%2e%2e/x"} {
		resp, _ := get(t, base+p, nil)
		if resp.StatusCode == 200 {
			t.Fatalf("traversal accepted: %s", p)
		}
	}
}

func TestConditional304(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	resp, _ := get(t, base+"/", nil)
	etag := resp.Header.Get("ETag")

	req, _ := http.NewRequest(http.MethodGet, base+"/", nil)
	req.Header.Set("If-None-Match", etag)
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusNotModified {
		t.Fatalf("want 304, got %d", resp2.StatusCode)
	}

	// Last-Modified path.
	lm := resp.Header.Get("Last-Modified")
	req3, _ := http.NewRequest(http.MethodGet, base+"/", nil)
	req3.Header.Set("If-Modified-Since", lm)
	resp3, err := http.DefaultClient.Do(req3)
	if err != nil {
		t.Fatal(err)
	}
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusNotModified {
		t.Fatalf("want 304 via IMS, got %d", resp3.StatusCode)
	}
}

func TestHeadAnd405(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	req, _ := http.NewRequest(http.MethodHead, base+"/", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("HEAD failed: %d", resp.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPost, base+"/", nil)
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("want 405, got %d", resp2.StatusCode)
	}
	if allow := resp2.Header.Get("Allow"); allow != "GET, HEAD" {
		t.Fatalf("bad Allow: %q", allow)
	}
}

func TestGzip(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	req, _ := http.NewRequest(http.MethodGet, base+"/assets/big.txt", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("expected gzip, got headers: %v", resp.Header)
	}
	if resp.Uncompressed { // transport already decoded — encoding header lied
		t.Fatal("client auto-decompressed; server did not gzip")
	}
	zr, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatalf("gzip body unreadable: %v", err)
	}
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, zr)
	if buf.Len() != 4096 {
		t.Fatalf("decompressed length %d, want 4096", buf.Len())
	}
}

func TestRange(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	resp, body := get(t, base+"/assets/big.txt", map[string]string{"Range": "bytes=0-99"})
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("want 206, got %d", resp.StatusCode)
	}
	if len(body) != 100 {
		t.Fatalf("range length %d, want 100", len(body))
	}
	if cr := resp.Header.Get("Content-Range"); !strings.HasPrefix(cr, "bytes 0-99/4096") {
		t.Fatalf("bad Content-Range: %q", cr)
	}
}

func TestSpaFallback(t *testing.T) {
	base, _, stop := newTestServer(t, true)
	defer stop()

	// Unknown client route without extension → index.html via SPA fallback.
	resp, body := get(t, base+"/users/42/profile", map[string]string{"Accept": "text/html"})
	if resp.StatusCode != 200 || !strings.Contains(body, "Main Home") {
		t.Fatalf("SPA fallback failed: %d %q", resp.StatusCode, body)
	}
	// Missing asset (has a dot) must stay 404 — no mask.
	resp2, _ := get(t, base+"/missing.css", nil)
	if resp2.StatusCode != 404 {
		t.Fatalf("missing asset should 404, got %d", resp2.StatusCode)
	}
	// SPA directory without its own index falls back to the site root.
	resp3, body3 := get(t, base+"/assets/", nil)
	if resp3.StatusCode != 200 || !strings.Contains(body3, "Main Home") {
		t.Fatalf("SPA dir fallback failed: %d %q", resp3.StatusCode, body3)
	}
}

func TestDirListing(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	// assets/ has no index → branded auto-index with every entry.
	resp, body := get(t, base+"/assets/", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200 listing, got %d", resp.StatusCode)
	}
	if !strings.Contains(body, "style.css") || !strings.Contains(body, "big.txt") {
		t.Fatalf("listing missing entries: %q", body)
	}
	if !strings.Contains(body, "Index of /assets") {
		t.Fatalf("listing missing title: %q", body)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Fatalf("bad content type: %q", ct)
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("missing nosniff header")
	}

	// Bare directory redirects to the trailing-slash form (no follow).
	noFollow := &http.Client{CheckRedirect: func(req *http.Request, via []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	req, _ := http.NewRequest(http.MethodGet, base+"/assets", nil)
	rresp, err := noFollow.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	rresp.Body.Close()
	if rresp.StatusCode != http.StatusMovedPermanently {
		t.Fatalf("bare dir should redirect, got %d", rresp.StatusCode)
	}
	if loc := rresp.Header.Get("Location"); loc != "/assets/" {
		t.Fatalf("bad redirect target: %q", loc)
	}

	// HEAD renders headers only.
	req, _ = http.NewRequest(http.MethodHead, base+"/assets/", nil)
	hresp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	hresp.Body.Close()
	if hresp.StatusCode != 200 {
		t.Fatalf("HEAD listing failed: %d", hresp.StatusCode)
	}

	// Directories sort before files; sizes are humanized.
	resp, body = get(t, base+"/assets/", nil)
	if !strings.Contains(body, "KB") && !strings.Contains(body, "B") {
		t.Fatalf("listing missing size column: %q", body)
	}

	// A site root with an index keeps serving it (no listing).
	_, body = get(t, base+"/", nil)
	if !strings.Contains(body, "Main Home") {
		t.Fatalf("index page displaced by listing: %q", body)
	}
}

func TestDirListingEscapeAndParent(t *testing.T) {
	dir := t.TempDir()
	site := filepath.Join(dir, "site")
	sub := filepath.Join(site, "docs")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(site, "weird <&>.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(sub, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}

	srv, err := NewServer("127.0.0.1", 0, []*SiteConfig{{ID: "m", Root: site, Default: true, Enabled: true}})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	defer srv.Stop()
	base := "http://" + srv.Addr()

	// Root listing: name HTML-escaped, no parent link at the root.
	resp, body := get(t, base+"/", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("root listing failed: %d", resp.StatusCode)
	}
	if strings.Contains(body, "weird <&>") {
		t.Fatalf("raw name leaked unescaped: %q", body)
	}
	if !strings.Contains(body, "weird &lt;&amp;&gt;.txt") {
		t.Fatalf("escaped name missing: %q", body)
	}
	if strings.Contains(body, `href="../"`) {
		t.Fatalf("root listing must not have a parent link: %q", body)
	}

	// Sub-directory listing: parent link present, entries reachable.
	resp, body = get(t, base+"/docs/", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("docs listing failed: %d", resp.StatusCode)
	}
	if !strings.Contains(body, `href="../"`) {
		t.Fatalf("sub listing missing parent link: %q", body)
	}
	if !strings.Contains(body, "a.txt") {
		t.Fatalf("sub listing missing entry: %q", body)
	}

	// The escaped link actually resolves back through the server.
	resp, _ = get(t, base+"/weird%20%3C%26%3E.txt", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("escaped name unreachable: %d", resp.StatusCode)
	}
}

func TestCustom404Page(t *testing.T) {
	dir := t.TempDir()
	site := filepath.Join(dir, "site")
	if err := os.MkdirAll(site, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(site, "index.html"), []byte("<h1>Home</h1>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(site, "404.html"), []byte("<h1>Custom Missing</h1>"), 0o644); err != nil {
		t.Fatal(err)
	}

	srv, err := NewServer("127.0.0.1", 0, []*SiteConfig{{ID: "m", Root: site, Default: true, Enabled: true}})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	defer srv.Stop()
	base := "http://" + srv.Addr()

	resp, body := get(t, base+"/missing-page.html", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("custom 404 must keep the real status, got %d", resp.StatusCode)
	}
	if !strings.Contains(body, "Custom Missing") {
		t.Fatalf("custom 404 body not served: %q", body)
	}

	// HEAD gets the status without a body.
	req, _ := http.NewRequest(http.MethodHead, base+"/missing-page.html", nil)
	hresp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	hresp.Body.Close()
	if hresp.StatusCode != http.StatusNotFound {
		t.Fatalf("HEAD custom 404 failed: %d", hresp.StatusCode)
	}

	// A site without 404.html keeps the branded page.
	base2, _, stop2 := newTestServer(t, false)
	defer stop2()
	resp, body = get(t, base2+"/nope.html", nil)
	if resp.StatusCode != http.StatusNotFound || !strings.Contains(body, "does not exist") {
		t.Fatalf("branded 404 lost: %d %q", resp.StatusCode, body)
	}
}

func TestWelcomeWhenNoDefault(t *testing.T) {
	dir := t.TempDir()
	only := filepath.Join(dir, "only")
	_ = os.MkdirAll(only, 0o755)
	_ = os.WriteFile(filepath.Join(only, "index.html"), []byte("<h1>Only</h1>"), 0o644)

	srv, err := NewServer("127.0.0.1", 0, []*SiteConfig{{ID: "only", Root: only, Enabled: true}})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Stop()
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	resp, body := get(t, "http://"+srv.Addr()+"/", nil)
	if resp.StatusCode != 200 || !strings.Contains(body, "Stratos is live") {
		t.Fatalf("welcome page missing: %d", resp.StatusCode)
	}
}

func TestHotReloadRegistry(t *testing.T) {
	base, main, stop := newTestServer(t, false)
	defer stop()

	srv := activeServerForTest(t)
	// Drop the secondary site; it must now 404.
	srv.SetSites([]*SiteConfig{{ID: "main", Root: main, Default: true, Enabled: true}})
	resp, _ := get(t, base+"/s/sec/", nil)
	if resp.StatusCode != 404 {
		t.Fatalf("secondary should be gone, got %d", resp.StatusCode)
	}
}

func activeServerForTest(t *testing.T) *Server {
	t.Helper()
	engineMu.Lock()
	defer engineMu.Unlock()
	if activeServer == nil {
		t.Fatal("no active server")
	}
	return activeServer
}

func TestBasicAuth(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	// No auth configured: open access.
	resp, _ := get(t, base+"/", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200 without auth, got %d", resp.StatusCode)
	}

	srv := activeServerForTest(t)
	srv.SetAuth("user", "s3cret")

	// Missing credentials: 401 + challenge.
	resp, body := get(t, base+"/", nil)
	if resp.StatusCode != 401 {
		t.Fatalf("want 401, got %d", resp.StatusCode)
	}
	if resp.Header.Get("WWW-Authenticate") == "" {
		t.Fatal("missing WWW-Authenticate challenge")
	}
	if !strings.Contains(body, "401") {
		t.Fatalf("unexpected body: %q", body)
	}

	// Wrong password: still 401.
	wrong := base64.StdEncoding.EncodeToString([]byte("user:nope"))
	resp, _ = get(t, base+"/", map[string]string{"Authorization": "Basic " + wrong})
	if resp.StatusCode != 401 {
		t.Fatalf("want 401 for wrong password, got %d", resp.StatusCode)
	}

	// Correct credentials: 200 and the real page.
	good := base64.StdEncoding.EncodeToString([]byte("user:s3cret"))
	resp, body = get(t, base+"/", map[string]string{"Authorization": "Basic " + good})
	if resp.StatusCode != 200 {
		t.Fatalf("want 200 with credentials, got %d", resp.StatusCode)
	}
	if !strings.Contains(body, "Main Home") {
		t.Fatalf("unexpected body: %q", body)
	}
}

func TestTopPaths(t *testing.T) {
	base, _, stop := newTestServer(t, false)
	defer stop()

	_, _ = get(t, base+"/", nil)
	_, _ = get(t, base+"/", nil)
	_, _ = get(t, base+"/about.html", nil)

	srv := activeServerForTest(t)
	top := srv.metrics.TopPaths(8)
	if len(top) == 0 {
		t.Fatal("no tracked paths")
	}
	if top[0].Path != "/" || top[0].Count < 2 {
		t.Fatalf("unexpected hottest path: %+v", top[0])
	}
	if top[1].Path != "/about.html" || top[1].Count != 1 {
		t.Fatalf("unexpected second path: %+v", top[1])
	}
	snap := srv.Snapshot()
	if _, ok := snap["topPaths"].([]PathCount); !ok {
		t.Fatalf("snapshot topPaths has wrong type: %T", snap["topPaths"])
	}
}

func TestPercentilesMonotonic(t *testing.T) {
	m := NewMetrics()
	for i := 0; i < 1000; i++ {
		m.Record(200, time.Duration(i%97)*time.Millisecond, 1)
	}
	snap := m.Snapshot()
	p50 := snap["p50Ms"].(float64)
	p95 := snap["p95Ms"].(float64)
	maxMs := snap["maxMs"].(float64)
	if !(p50 <= p95 && p95 <= maxMs) {
		t.Fatalf("percentile invariant broken: %v %v %v", p50, p95, maxMs)
	}
}
