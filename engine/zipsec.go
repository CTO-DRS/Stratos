package engine

import (
	"archive/zip"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// Hard limits mirroring (and slightly raising) the PocketHost-class threat
// model. Validation happens BEFORE anything touches storage.
const (
	maxZipEntries           = 20_000
	maxZipTotalUncompressed = 512 << 20 // 512 MB
	maxEntryUncompressed    = 256 << 20 // 256 MB per entry
	maxZipRatio             = 500       // enforced only for entries whose compressed size exceeds 1 MB
)

// ExtractResult is the summary shown to the user before hosting.
type ExtractResult struct {
	Files      int    `json:"files"`
	Dirs       int    `json:"dirs"`
	Bytes      int64  `json:"bytes"`
	Root       string `json:"root"`       // detected site root, relative to dest
	HasIndex   bool   `json:"hasIndex"`   // index.html/.htm found at Root
	DestDir    string `json:"destDir"`    // final absolute destination
	TopLevel   string `json:"topLevel"`   // hint of the archive layout
	EntryCount int    `json:"entryCount"` // total entries seen (incl. dirs)
}

// ValidationError carries a user-presentable reason.
type ValidationError struct{ Reason string }

func (e *ValidationError) Error() string { return e.Reason }

func vErrf(format string, args ...any) error {
	return &ValidationError{Reason: fmt.Sprintf(format, args...)}
}

// ValidateAndExtract validates a ZIP archive and extracts it atomically into
// destDir. If anything fails, destDir is left untouched.
//
// Defences:
//   - ZIP-Slip: path traversal ("..", absolute paths, drive letters, NULs)
//   - Symlink entries (refused outright)
//   - Zip bombs: entry count, total and per-entry size, compression ratio
//   - Encrypted entries, duplicate names, broken CRCs (per-entry, on read)
func ValidateAndExtract(zipPath, destDir string) (*ExtractResult, error) {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return nil, vErrf("cannot open archive: %v", err)
	}
	defer zr.Close()

	if len(zr.File) == 0 {
		return nil, vErrf("archive is empty")
	}
	if len(zr.File) > maxZipEntries {
		return nil, vErrf("too many entries: %d (limit %d)", len(zr.File), maxZipEntries)
	}

	seen := make(map[string]struct{}, len(zr.File))
	var totalUncompressed int64
	var files, dirs int

	tmpDir := destDir + ".tmp-" + randomHex(6)
	if err := os.MkdirAll(tmpDir, 0o755); err != nil {
		return nil, fmt.Errorf("cannot prepare temp dir: %v", err)
	}
	cleanup := func() { _ = os.RemoveAll(tmpDir) }
	committed := false
	defer func() {
		if !committed {
			cleanup()
		}
	}()

	for _, zf := range zr.File {
		name, err := sanitizeName(zf.Name)
		if err != nil {
			return nil, err
		}
		if _, dup := seen[name]; dup {
			return nil, vErrf("duplicate entry name: %s", name)
		}
		seen[name] = struct{}{}

		if zf.FileInfo().Mode()&os.ModeSymlink != 0 {
			return nil, vErrf("symlink entries are not allowed: %s", name)
		}
		if zf.Flags&0x1 != 0 {
			return nil, vErrf("encrypted entries are not allowed: %s", name)
		}

		if strings.HasSuffix(name, "/") {
			dirs++
		} else {
			files++
			if int64(zf.UncompressedSize64) > maxEntryUncompressed {
				return nil, vErrf("entry too large: %s (%d bytes)", name, zf.UncompressedSize64)
			}
			if int64(zf.UncompressedSize64) > 1<<20 &&
				int64(zf.UncompressedSize64)/int64(max(zf.CompressedSize64, 1)) > maxZipRatio {
				return nil, vErrf("compression ratio too high (possible zip bomb): %s", name)
			}
		}
		totalUncompressed += int64(zf.UncompressedSize64)
		if totalUncompressed > maxZipTotalUncompressed {
			return nil, vErrf("archive expands beyond the %d MB limit", maxZipTotalUncompressed/(1<<20))
		}

		target := filepath.Join(tmpDir, filepath.FromSlash(name))
		if strings.HasSuffix(name, "/") {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return nil, fmt.Errorf("mkdir %s: %v", name, err)
			}
			continue
		}
		if err := extractEntry(zf, target); err != nil {
			return nil, err
		}
	}

	// Empty archives that only contain directories are useless.
	if files == 0 {
		return nil, vErrf("archive contains no files")
	}

	root, hasIndex, topLevel := detectRootIn(tmpDir)
	if err := os.MkdirAll(filepath.Dir(destDir), 0o755); err != nil {
		return nil, fmt.Errorf("cannot prepare destination: %v", err)
	}
	if err := os.Rename(tmpDir, destDir); err != nil {
		return nil, fmt.Errorf("cannot commit extraction: %v", err)
	}
	committed = true

	return &ExtractResult{
		Files:      files,
		Dirs:       dirs,
		Bytes:      totalUncompressed,
		Root:       root,
		HasIndex:   hasIndex,
		DestDir:    destDir,
		TopLevel:   topLevel,
		EntryCount: len(zr.File),
	}, nil
}

// extractEntry copies one regular file with a per-entry CRC32 verification
// (Go's archive/zip verifies the checksum when the entry reader reaches EOF;
// io.Copy consumes it fully, so a corrupted entry surfaces as an error).
func extractEntry(zf *zip.File, target string) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return fmt.Errorf("mkdir for %s: %v", zf.Name, err)
	}
	rc, err := zf.Open()
	if err != nil {
		return vErrf("cannot read entry %s: %v", zf.Name, err)
	}
	defer rc.Close()

	out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("create %s: %v", zf.Name, err)
	}
	written, copyErr := io.Copy(out, rc)
	closeErr := out.Close()
	if copyErr != nil {
		return vErrf("entry %s failed verification (CRC or stream): %v", zf.Name, copyErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close %s: %v", zf.Name, closeErr)
	}
	if written != int64(zf.UncompressedSize64) {
		return vErrf("entry %s size mismatch", zf.Name)
	}
	return nil
}

// sanitizeName rejects dangerous entry names and returns a clean,
// slash-separated relative path.
func sanitizeName(raw string) (string, error) {
	if raw == "" {
		return "", vErrf("empty entry name")
	}
	if strings.ContainsRune(raw, 0) {
		return "", vErrf("NUL byte in entry name")
	}
	if strings.ContainsRune(raw, '\\') {
		raw = strings.ReplaceAll(raw, "\\", "/")
	}
	if path.IsAbs(raw) || strings.HasPrefix(raw, "/") {
		return "", vErrf("absolute path entry: %s", raw)
	}
	if len(raw) >= 2 && raw[1] == ':' {
		return "", vErrf("drive-letter path entry: %s", raw)
	}
	for _, seg := range strings.Split(raw, "/") {
		if seg == ".." {
			return "", vErrf("path traversal entry: %s", raw)
		}
	}
	clean := path.Clean("/" + raw) // forces relative
	rel := strings.TrimPrefix(clean, "/")
	if rel == "." || rel == "" {
		return "", vErrf("empty path after clean: %s", raw)
	}
	return rel, nil
}

// detectRootIn finds the real website root inside an extracted directory:
//  1. index.html/.htm at the top level
//  2. exactly one top-level folder that contains index.html/.htm
//  3. public/index.html/.htm
//  4. otherwise the extraction dir itself (hasIndex=false)
func detectRootIn(dir string) (rel string, hasIndex bool, topLevel string) {
	if hasIndexAt(dir) {
		return ".", true, "."
	}
	entries, err := os.ReadDir(dir)
	if err == nil {
		var dirs []string
		for _, e := range entries {
			if e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
				dirs = append(dirs, e.Name())
			}
		}
		if len(dirs) == 1 && hasIndexAt(filepath.Join(dir, dirs[0])) {
			return dirs[0], true, dirs[0]
		}
		if hasIndexAt(filepath.Join(dir, "public")) {
			return "public", true, "public"
		}
		if len(dirs) == 1 {
			return dirs[0], false, dirs[0]
		}
	}
	return ".", false, "."
}

func hasIndexAt(dir string) bool {
	st, err := os.Stat(filepath.Join(dir, "index.html"))
	if err == nil && !st.IsDir() {
		return true
	}
	st, err = os.Stat(filepath.Join(dir, "index.htm"))
	return err == nil && !st.IsDir()
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
