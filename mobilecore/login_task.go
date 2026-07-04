package mobilecore

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// Challenge/login status constants
const (
	ChallengeTypeImageOCR = "image_ocr"
	ChallengeTypeSlider   = "slider"
	ChallengeTypeFace     = "face"

	LoginStatusDone      = "done"
	LoginStatusChallenge = "challenge"
	LoginStatusCancelled = "cancelled"
)

// StartLoginResult is the data payload returned by StartLogin.
type StartLoginResult struct {
	Status    string        `json:"status"`              // done | challenge
	Session   *SessionData  `json:"session,omitempty"`   // present when status=done
	TaskID    string        `json:"taskId,omitempty"`    // present when status=challenge
	Challenge *OcrChallenge `json:"challenge,omitempty"` // present when status=challenge
}

// ContinueLoginResult is the data payload returned by ContinueLogin.
type ContinueLoginResult struct {
	Status    string        `json:"status"`
	Session   *SessionData  `json:"session,omitempty"`
	TaskID    string        `json:"taskId,omitempty"`
	Challenge *OcrChallenge `json:"challenge,omitempty"`
}

// CancelLoginResult is the data payload returned by CancelLogin.
type CancelLoginResult struct {
	Status string `json:"status"` // cancelled
	TaskID string `json:"taskId"`
}

// PendingLoginTask is an in-flight multi-step login session (e.g. OCR flow).
type PendingLoginTask struct {
	TaskID    string
	Platform  string
	Account   string
	Step      int
	Extra     map[string]interface{} // platform-specific state (sessionId, partial cookies, etc.)
	CreatedAt time.Time
}

var loginTaskSeq uint64

type loginTaskStore struct {
	mu    sync.Mutex
	tasks map[string]*PendingLoginTask
}

var pendingLogins = &loginTaskStore{tasks: make(map[string]*PendingLoginTask)}

func newLoginTaskID() string {
	n := atomic.AddUint64(&loginTaskSeq, 1)
	return fmt.Sprintf("login-%d", n)
}

func (s *loginTaskStore) create(platform, account string) *PendingLoginTask {
	t := &PendingLoginTask{
		TaskID:    newLoginTaskID(),
		Platform:  platform,
		Account:   account,
		Extra:     make(map[string]interface{}),
		CreatedAt: time.Now(),
	}
	s.mu.Lock()
	s.tasks[t.TaskID] = t
	s.mu.Unlock()
	return t
}

func (s *loginTaskStore) setExtra(taskID string, extra map[string]interface{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.tasks[taskID]; ok {
		t.Extra = extra
	}
}

func (s *loginTaskStore) get(taskID string) (*PendingLoginTask, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.tasks[taskID]
	return t, ok
}

func (s *loginTaskStore) delete(taskID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.tasks[taskID]
	delete(s.tasks, taskID)
	return ok
}
