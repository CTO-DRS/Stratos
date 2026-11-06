package engine

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// buildZip writes an in-memory ZIP with the given entries.
func buildZip(t *testing.T, entries map[string]string) string {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(t.TempDir(), "site.zip")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestZipHappyPath(t *testing.T) {
	zipPath := buildZip(t, map[string]string{
		"index.html":            "<h1>Hi</h1>",
		"css/main.css":          "body{}",
		"images/logo.svg":       "<svg/>",
		"nested/deep/page.html": "<p>deep</p>",
	})
	dest := filepath.Join(t.TempDir(), "site")
	res, err := ValidateAndExtract(zipPath, dest)
	if err != nil {
		t.Fatalf("extract failed: %v", err)
	}
	if res.Files != 4 || res.Bytes != int64(len("<h1>Hi</h1>")+len("body{}")+len("<svg/>")+len("<p>deep</p>")) {
		t.Fatalf("bad summary: %+v", res)
	}
	if res.Root != "." || !res.HasIndex {
		t.Fatalf("root detect wrong: %q %v", res.Root, res.HasIndex)
	}
	if _, err := os.Stat(filepath.Join(dest, "css", "main.css")); err != nil {
		t.Fatalf("file missing: %v", err)
	}
}

func TestZipDetectsSingleTopFolder(t *testing.T) {
	zipPath := buildZip(t, map[string]string{
		"dist/index.html": "<h1>Dist</h1>",
		"dist/app.js":     "console.log(1)",
	})
	dest := filepath.Join(t.TempDir(), "site")
	res, err := ValidateAndExtract(zipPath, dest)
	if err != nil {
		t.Fatal(err)
	}
	if res.Root != "dist" || !res.HasIndex {
		t.Fatalf("expected dist root, got %+v", res)
	}
}

func TestZipSlipRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{
		"index.html":   "ok",
		"../evil.html": "nope",
	})
	dest := filepath.Join(t.TempDir(), "site")
	_, err := ValidateAndExtract(zipPath, dest)
	if err == nil || !strings.Contains(err.Error(), "traversal") {
		t.Fatalf("want traversal rejection, got %v", err)
	}
	// Nothing may be committed.
	if _, statErr := os.Stat(dest); !os.IsNotExist(statErr) {
		t.Fatal("destination must not exist after failure")
	}
}

func TestZipAbsolutePathRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{"index.html": "ok", "/etc/passwd": "x"})
	_, err := ValidateAndExtract(zipPath, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "absolute") {
		t.Fatalf("want absolute-path rejection, got %v", err)
	}
}

func TestZipDriveLetterRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{"index.html": "ok", "C:\\evil.txt": "x"})
	_, err := ValidateAndExtract(zipPath, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "drive-letter") {
		t.Fatalf("want drive-letter rejection, got %v", err)
	}
}

func TestZipDuplicateNamesRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{"a.txt": "1", "A.txt": "2"})
	// Note: duplicate detection is exact-name; the builder dedups map keys,
	// so craft duplicates manually:
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, name := range []string{"a.txt", "a.txt"} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte("x"))
	}
	_ = zw.Close()
	if err := os.WriteFile(zipPath, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := ValidateAndExtract(zipPath, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "duplicate") {
		t.Fatalf("want duplicate rejection, got %v", err)
	}
}

func TestZipEncryptedFlagRejected(t *testing.T) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("index.html")
	_, _ = w.Write([]byte("ok"))
	_ = zw.Close()

	// Flip the encryption bit in the CENTRAL DIRECTORY header (the reader
	// takes flags from there, not from the local header).
	// Central header: sig(4) verMadeBy(2) verNeeded(2) flags(2)@offset 8.
	raw := buf.Bytes()
	for i := 0; i+12 < len(raw); i++ {
		if raw[i] == 0x50 && raw[i+1] == 0x4B && raw[i+2] == 0x01 && raw[i+3] == 0x02 {
			raw[i+8] |= 0x01
			break
		}
	}
	p := filepath.Join(t.TempDir(), "enc.zip")
	_ = os.WriteFile(p, raw, 0o644)

	_, err := ValidateAndExtract(p, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "encrypted") {
		t.Fatalf("want encrypted rejection, got %v", err)
	}
}

func TestZipSymlinkRejected(t *testing.T) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	hdr := &zip.FileHeader{Name: "link"}
	hdr.SetMode(os.ModeSymlink | 0o777)
	w, _ := zw.CreateHeader(hdr)
	_, _ = w.Write([]byte("/etc/passwd"))
	_ = zw.Close()
	p := filepath.Join(t.TempDir(), "link.zip")
	_ = os.WriteFile(p, buf.Bytes(), 0o644)

	_, err := ValidateAndExtract(p, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "symlink") {
		t.Fatalf("want symlink rejection, got %v", err)
	}
}

func TestZipBombRatioRejected(t *testing.T) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("payload.bin")
	payload := bytes.Repeat([]byte{0}, 8<<20) // 8 MB of zeros
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	_ = zw.Close()
	p := filepath.Join(t.TempDir(), "bomb.zip")
	_ = os.WriteFile(p, buf.Bytes(), 0o644)

	_, err := ValidateAndExtract(p, filepath.Join(t.TempDir(), "site"))
	if err == nil || !strings.Contains(err.Error(), "ratio") {
		t.Fatalf("want bomb rejection, got %v", err)
	}
}

func TestZipCorruptCRCRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{"index.html": "payload that will be corrupted"})
	raw, _ := os.ReadFile(zipPath)
	// Corrupt one byte inside the file data region: local header is
	// 30 + len("index.html") = 40 bytes, so offset 42 is inside the payload.
	raw[42] ^= 0xFF
	bad := filepath.Join(t.TempDir(), "bad.zip")
	_ = os.WriteFile(bad, raw, 0o644)

	_, err := ValidateAndExtract(bad, filepath.Join(t.TempDir(), "site"))
	if err == nil {
		t.Fatal("want CRC rejection")
	}
}

func TestZipEmptyRejected(t *testing.T) {
	zipPath := buildZip(t, map[string]string{})
	_, err := ValidateAndExtract(zipPath, filepath.Join(t.TempDir(), "site"))
	if err == nil {
		t.Fatal("want empty rejection")
	}
}

func TestZipReplacesAtomically(t *testing.T) {
	// A failed extraction must not leave a partial destination behind.
	zipPath := buildZip(t, map[string]string{"index.html": "ok", "../escape": "x"})
	dest := filepath.Join(t.TempDir(), "site")
	if _, err := ValidateAndExtract(zipPath, dest); err == nil {
		t.Fatal("expected failure")
	}
	entries, _ := os.ReadDir(filepath.Dir(dest))
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "site.tmp-") {
			t.Fatalf("temp dir leaked: %s", e.Name())
		}
	}
}
