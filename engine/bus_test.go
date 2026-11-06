package engine

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestBusPing(t *testing.T) {
	resp := dispatch(`{"cmd":"ping"}`)
	if !strings.Contains(resp, `"ok":true`) || !strings.Contains(resp, "go") {
		t.Fatalf("ping failed: %s", resp)
	}
}

func TestBusLifecycleEndToEnd(t *testing.T) {
	// start → status → HTTP round trip → metrics.reset → stop
	dir := t.TempDir()
	root := dir + "/main"
	if err := writeTree(root); err != nil {
		t.Fatal(err)
	}

	sites := `[{"id":"main","root":"` + root + `","default":true,"enabled":true}]`
	resp := dispatch(`{"cmd":"start","port":0,"bind":"127.0.0.1","sites":` + sites + `}`)
	// port 0 is invalid at the bus level? No — port 0 means auto-assign here.
	if !strings.Contains(resp, `"ok":true`) {
		t.Fatalf("start failed: %s", resp)
	}

	var started struct {
		Data struct {
			Addr string `json:"addr"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(resp), &started); err != nil {
		t.Fatal(err)
	}
	addr := started.Data.Addr
	if addr == "" {
		t.Fatal("no addr returned")
	}

	resp = dispatch(`{"cmd":"status"}`)
	if !strings.Contains(resp, `"running":true`) {
		t.Fatalf("status not running: %s", resp)
	}

	// Real HTTP round trip through the bus-started server.
	body, code := httpGet(t, "http://"+addr+"/")
	if code != 200 || !strings.Contains(body, "Bus Site") {
		t.Fatalf("round trip failed: %d %q", code, body)
	}

	dispatch(`{"cmd":"metrics.reset"}`)
	resp = dispatch(`{"cmd":"status"}`)
	if strings.Contains(resp, `"requests":0`) == false && strings.Contains(resp, `"requests":1`) == false {
		t.Fatalf("metrics not numeric: %s", resp)
	}

	resp = dispatch(`{"cmd":"stop"}`)
	if !strings.Contains(resp, `"ok":true`) {
		t.Fatalf("stop failed: %s", resp)
	}
	resp = dispatch(`{"cmd":"status"}`)
	if !strings.Contains(resp, `"running":false`) {
		t.Fatalf("still running after stop: %s", resp)
	}
}

func TestBusUnknownCommand(t *testing.T) {
	resp := dispatch(`{"cmd":"explode"}`)
	if !strings.Contains(resp, `"ok":false`) {
		t.Fatalf("unknown cmd should fail: %s", resp)
	}
}

func TestBusBadJSON(t *testing.T) {
	resp := dispatch(`{not json`)
	if !strings.Contains(resp, `"ok":false`) {
		t.Fatalf("bad json should fail: %s", resp)
	}
}

func TestRedactTokens(t *testing.T) {
	// Realistic Cloudflare tunnel token: a single long base64url segment.
	in := `cloudflared service install eyJhIjoiSGVsbG8sX1dvcmxkIV9IZXJlIiwidCI6IjEyMzQ1Njc4OTAxMjM0NTY3ODkwIn0`
	out := Redact(in)
	if strings.Contains(out, "SGVsbG8sX1dvcmxk") || strings.Contains(out, "OTAxMjM0NTY3ODkw") {
		t.Fatalf("token leaked: %s", out)
	}
	if !strings.Contains(out, "[redacted]") {
		t.Fatalf("no redaction marker: %s", out)
	}

	// Full 3-segment JWT.
	jwt := `resp: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5NXgL0n3I9Pl`
	if strings.Contains(Redact(jwt), "dozjgNryP4J3jVmNHl0w5NXgL0n3I9Pl") {
		t.Fatalf("jwt leaked: %s", Redact(jwt))
	}

	// Bearer header.
	in2 := `Authorization: Bearer supersecrettoken123456`
	if strings.Contains(Redact(in2), "supersecrettoken123456") {
		t.Fatal("bearer leaked")
	}
}

func TestLogTailRedacted(t *testing.T) {
	appendLog("INFO", "token=eyJaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbb.cccc")
	resp := dispatch(`{"cmd":"log.tail","n":5}`)
	if strings.Contains(resp, "eyJaaaaaaaa") {
		t.Fatalf("log tail leaked token: %s", resp)
	}
}

func TestPortValidation(t *testing.T) {
	resp := dispatch(`{"cmd":"start","port":70000,"sites":[]}`)
	if !strings.Contains(resp, `"ok":false`) {
		t.Fatalf("port 70000 must fail: %s", resp)
	}
}

// ---- helpers --------------------------------------------------------------

func writeTree(root string) error {
	if err := mkDirs(root); err != nil {
		return err
	}
	return osWrite(root+"/index.html", []byte("<h1>Bus Site</h1>"))
}

func httpGet(t *testing.T, url string) (string, int) {
	t.Helper()
	resp, err := httpClient.Get(url)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer resp.Body.Close()
	var sb strings.Builder
	buf := make([]byte, 4096)
	for {
		n, err := resp.Body.Read(buf)
		sb.Write(buf[:n])
		if err != nil {
			break
		}
	}
	return sb.String(), resp.StatusCode
}

func TestBusZipExtract(t *testing.T) {
	dir := t.TempDir()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("index.html")
	_, _ = w.Write([]byte("<h1>Bus Zip</h1>"))
	_ = zw.Close()
	zipPath := dir + "/s.zip"
	_ = os.WriteFile(zipPath, buf.Bytes(), 0o644)
	dest := dir + "/site"

	resp := dispatch(`{"cmd":"zip.extract","extra":{"zipPath":"` + zipPath + `","destDir":"` + dest + `"}}`)
	if !strings.Contains(resp, `"ok":true`) || !strings.Contains(resp, `"hasIndex":true`) {
		t.Fatalf("zip.extract failed: %s", resp)
	}
	if _, err := os.Stat(dest + "/index.html"); err != nil {
		t.Fatalf("not extracted: %v", err)
	}
}
