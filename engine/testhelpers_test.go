package engine

import (
	"net/http"
	"os"
	"path/filepath"
)

// testHelpers.go — shared test utilities.

var httpClient = &http.Client{}

func mkDirs(p string) error {
	return os.MkdirAll(p, 0o755)
}

func osWrite(p string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	return os.WriteFile(p, data, 0o644)
}
