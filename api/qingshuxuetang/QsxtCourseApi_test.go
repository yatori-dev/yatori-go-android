package qingshuxuetang

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestQsxtPullNodeApiReturnsRequestError(t *testing.T) {
	_, err := (&QsxtUserCache{}).QsxtPullNodeApi("://bad-url", 0, nil)
	if err == nil {
		t.Fatal("invalid node URL must return the request construction error")
	}
}

func TestQsxtPullNodeApiReturnsUnauthorizedStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"hr":401}`))
	}))
	defer server.Close()

	_, err := (&QsxtUserCache{Token: "expired"}).QsxtPullNodeApi(server.URL, 0, nil)
	if err == nil || !strings.Contains(err.Error(), "401 Unauthorized") {
		t.Fatalf("error=%v, want HTTP 401 Unauthorized", err)
	}
}
