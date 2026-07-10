package mobile

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func withoutYinghuaRetrySleep(t *testing.T) {
	t.Helper()
	previous := yinghuaRetrySleep
	yinghuaRetrySleep = func(time.Duration) {}
	t.Cleanup(func() { yinghuaRetrySleep = previous })
}

func TestRetryYinghuaSafeRequestRetriesTransportErrors(t *testing.T) {
	withoutYinghuaRetrySleep(t)
	calls := 0
	body, err := retryYinghuaSafeRequest(2, true, func() ([]byte, error) {
		calls++
		if calls < 3 {
			return nil, errors.New("temporary network failure")
		}
		return []byte(`{"status":true,"_code":0}`), nil
	})
	if err != nil {
		t.Fatalf("retry transport errors: %v", err)
	}
	if calls != 3 || string(body) != `{"status":true,"_code":0}` {
		t.Fatalf("calls=%d body=%s", calls, body)
	}
}

func TestSubmitStudyTimeRetriesTransientGatewayResponse(t *testing.T) {
	withoutYinghuaRetrySleep(t)
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) < 3 {
			_, _ = w.Write([]byte("<html>502 Bad Gateway</html>"))
			return
		}
		_, _ = w.Write([]byte(`{"_code":0,"status":true,"msg":"提交学时成功!","result":{"data":{"studyId":123}}}`))
	}))
	defer server.Close()

	client := &YingHuaClient{PreURL: server.URL, Token: "token"}
	raw, err := client.SubmitStudyTime("node", "0", 5)
	if err != nil {
		t.Fatalf("submit after transient gateway responses: %v", err)
	}
	if calls.Load() != 3 {
		t.Fatalf("calls=%d, want 3", calls.Load())
	}
	if _, err := ParseStudySubmitResult(raw); err != nil {
		t.Fatalf("final response should be parseable: %v", err)
	}
}

func TestSubmitStudyTimeDoesNotRetryAuthenticationExpiry(t *testing.T) {
	withoutYinghuaRetrySleep(t)
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_code":500,"status":false,"msg":"账号登录超时，请重新登录","result":{}}`))
	}))
	defer server.Close()

	client := &YingHuaClient{PreURL: server.URL, Token: "expired"}
	raw, err := client.SubmitStudyTime("node", "study", 10)
	if err != nil {
		t.Fatalf("auth response must reach the parser instead of becoming a retry error: %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("calls=%d, want 1", calls.Load())
	}
	if _, err := ParseStudySubmitResult(raw); err == nil || err.Error() != "yinghua: study submit failed: 账号登录超时，请重新登录" {
		t.Fatalf("unexpected parser error: %v", err)
	}
}

func TestKeepAliveReportsExpiryWithoutRetryingCode500(t *testing.T) {
	withoutYinghuaRetrySleep(t)
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_code":500,"status":false,"msg":"账号登录超时，请重新登录"}`))
	}))
	defer server.Close()

	client := &YingHuaClient{PreURL: server.URL, Token: "expired"}
	raw, err := client.KeepAlive()
	if err != nil {
		t.Fatalf("keepalive expiry should be returned to the host: %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("calls=%d, want 1", calls.Load())
	}
	alive, expired := ParseKeepAliveResult(raw)
	if alive || !expired {
		t.Fatalf("alive=%v expired=%v, want false/true", alive, expired)
	}
}
