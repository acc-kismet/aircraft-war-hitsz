package main

import "testing"

func TestDefaultDBPath(t *testing.T) {
	if got := defaultDBPath(); got != "aircraft-war.sqlite" {
		t.Fatalf("defaultDBPath() = %q, want %q", got, "aircraft-war.sqlite")
	}
}
