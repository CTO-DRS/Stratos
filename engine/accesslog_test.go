package engine

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

// TestAccessRingBounded verifies the per-server access ring keeps the newest
// entries and evicts the oldest once the cap is exceeded.
func TestAccessRingBounded(t *testing.T) {
	srv := &Server{}
	total := accessRingSize + 25
	for i := 0; i < total; i++ {
		srv.noteAccess(accessEntry{At: int64(i), Method: "GET", Path: "/", Status: 200, Ms: 1})
	}
	got := srv.TailAccess(accessRingSize)
	if len(got) != accessRingSize {
		t.Fatalf("ring size = %d, want %d", len(got), accessRingSize)
	}
	if got[0].At != int64(total-1) {
		t.Fatalf("newest-first violated: head At=%d, want %d", got[0].At, total-1)
	}
	if got[len(got)-1].At != int64(25) {
		t.Fatalf("oldest evicted wrongly: tail At=%d, want 25", got[len(got)-1].At)
	}
}

// TestTailAccessCap verifies n clamping and ordering.
func TestTailAccessCap(t *testing.T) {
	srv := &Server{}
	for i := 0; i < 10; i++ {
		srv.noteAccess(accessEntry{At: int64(i), Status: 200})
	}
	if got := srv.TailAccess(500); len(got) != 10 {
		t.Fatalf("TailAccess(500) = %d entries, want 10", len(got))
	}
	got := srv.TailAccess(3)
	if len(got) != 3 || got[0].At != 9 || got[1].At != 8 || got[2].At != 7 {
		t.Fatalf("TailAccess(3) wrong: %+v", got)
	}
}

// TestAccessPathRedactedAndCapped: token material in a path never survives,
// and overly long paths are truncated before storage.
func TestAccessPathRedactedAndCapped(t *testing.T) {
	srv := &Server{}
	tokenPath := "/x?token=eyJaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbb.cccc"
	longPath := "/" + strings.Repeat("a", 300)
	srv.noteAccess(accessEntry{Method: "GET", Path: redactPath(tokenPath), Status: 200})
	srv.noteAccess(accessEntry{Method: "GET", Path: redactPath(longPath), Status: 200})
	got := srv.TailAccess(2)
	if len(got) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(got))
	}
	// Newest first: [0] = long path, [1] = token path.
	if len(got[0].Path) > accessPathCap {
		t.Fatalf("long path not capped: %d chars", len(got[0].Path))
	}
	if strings.Contains(got[1].Path, "eyJaaaaaaaa") {
		t.Fatalf("token survived in path: %s", got[1].Path)
	}
	if !strings.Contains(got[1].Path, "[redacted]") {
		t.Fatalf("expected redaction marker, got: %s", got[1].Path)
	}
}

// TestResetAccess clears the ring.
func TestResetAccess(t *testing.T) {
	srv := &Server{}
	srv.noteAccess(accessEntry{Method: "GET", Path: "/", Status: 200})
	if got := srv.TailAccess(10); len(got) != 1 {
		t.Fatalf("expected 1 entry before reset, got %d", len(got))
	}
	srv.resetAccess()
	if got := srv.TailAccess(10); len(got) != 0 {
		t.Fatalf("expected empty ring after reset, got %d", len(got))
	}
}

// TestAccessLogViaHTTP: real requests land in the ring with correct status,
// method, path and order (newest first).
func TestAccessLogViaHTTP(t *testing.T) {
	base, _, cleanup := newTestServer(t, false)
	defer cleanup()

	resp, err := httpClient.Get(base + "/")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	resp, err = httpClient.Get(base + "/missing-page")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()

	engineMu.Lock()
	srv := activeServer
	engineMu.Unlock()
	if srv == nil {
		t.Fatal("no active server")
	}
	got := srv.TailAccess(10)
	if len(got) != 2 {
		t.Fatalf("expected 2 access entries, got %d", len(got))
	}
	if got[0].Path != "/missing-page" || got[0].Status != http.StatusNotFound || got[0].Method != "GET" {
		t.Fatalf("newest entry wrong: %+v", got[0])
	}
	if got[1].Path != "/" || got[1].Status != http.StatusOK {
		t.Fatalf("older entry wrong: %+v", got[1])
	}
	if got[0].At <= 0 || got[0].Ms < 0 {
		t.Fatalf("bad timestamps: %+v", got[0])
	}
}

// TestAccessLogSkipsAuthFailures: 401 challenges are never recorded, but the
// same request with valid credentials is recorded exactly once.
func TestAccessLogSkipsAuthFailures(t *testing.T) {
	base, _, cleanup := newTestServer(t, false)
	defer cleanup()

	engineMu.Lock()
	srv := activeServer
	engineMu.Unlock()
	if srv == nil {
		t.Fatal("no active server")
	}
	srv.SetAuth("user", "secret")

	// Unauthenticated probe → 401, not recorded.
	resp, err := httpClient.Get(base + "/")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if got := srv.TailAccess(10); len(got) != 0 {
		t.Fatalf("401 leaked into access log: %+v", got)
	}

	// Same path with credentials → recorded once, 200.
	req, _ := http.NewRequest("GET", base+"/", nil)
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte("user:secret")))
	resp, err = httpClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	got := srv.TailAccess(10)
	if len(got) != 1 {
		t.Fatalf("expected exactly 1 entry after authed request, got %d", len(got))
	}
	if got[0].Status != http.StatusOK || got[0].Path != "/" {
		t.Fatalf("authed entry wrong: %+v", got[0])
	}
}

// TestBusLogAccess: the bus command answers newest-first JSON lines, an
// empty array when no engine runs, and clamps oversized n.
func TestBusLogAccess(t *testing.T) {
	// No active server.
	out := dispatch(`{"cmd":"log.access","n":50}`)
	if !strings.Contains(out, `"ok":true`) || !strings.Contains(out, `"lines":[]`) {
		t.Fatalf("idle response wrong: %s", out)
	}

	base, _, cleanup := newTestServer(t, false)
	defer cleanup()
	resp, err := httpClient.Get(base + "/about.html")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()

	out = dispatch(`{"cmd":"log.access","n":5}`)
	var parsed struct {
		OK   bool `json:"ok"`
		Data struct {
			Lines []accessEntry `json:"lines"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(out), &parsed); err != nil {
		t.Fatalf("bad json: %v — %s", err, out)
	}
	if !parsed.OK || len(parsed.Data.Lines) != 1 {
		t.Fatalf("expected 1 line, got: %s", out)
	}
	if parsed.Data.Lines[0].Path != "/about.html" {
		t.Fatalf("wrong path in bus response: %s", out)
	}
}
