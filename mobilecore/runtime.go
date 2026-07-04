package mobilecore

import "sync"

const Version = "0.1.1-mobile"

type runtimeState struct {
	mu          sync.RWMutex
	baseDir     string
	config      *MobileConfig
	initialized bool
}

// state is the package-level singleton; reset in tests via state = runtimeState{}.
var state runtimeState
