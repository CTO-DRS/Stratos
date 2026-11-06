// Command stratosd is a tiny development runner for the Stratos engine:
// it serves one directory on the given port so the engine can be exercised
// from a plain terminal (no Android) during development and CI.
//
// Usage: go run ./cmd/stratosd [dir] [port]
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	engine "stratos/engine"
)

func main() {
	dir := "."
	port := 8080
	if len(os.Args) > 1 {
		dir = os.Args[1]
	}
	if len(os.Args) > 2 {
		fmt.Sscanf(os.Args[2], "%d", &port)
	}
	abs, err := filepath.Abs(dir)
	if err != nil {
		panic(err)
	}

	sites := []map[string]any{{
		"id": "main", "root": abs, "default": true, "enabled": true,
	}}
	sitesJSON, _ := json.Marshal(sites)

	req := fmt.Sprintf(`{"cmd":"start","port":%d,"bind":"0.0.0.0","sites":%s}`, port, sitesJSON)
	resp := engine.Command(req)
	fmt.Println("→", req)
	fmt.Println("←", resp)
	fmt.Println("Serving", abs, "on http://0.0.0.0:"+fmt.Sprint(port), "— Ctrl+C to stop")
	select {}
}
