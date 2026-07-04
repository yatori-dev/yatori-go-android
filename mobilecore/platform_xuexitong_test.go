package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func fakeXxtCourseList(raw string, err error) func() {
	orig := xxtCourseListProvider
	xxtCourseListProvider = func(_ *xxtmobile.XxtClient) (string, error) { return raw, err }
	return func() { xxtCourseListProvider = orig }
}

const xxtSessJSON = `{"platform":"xuexitong","cookies":"SESS=x","extra":{"userId":"u1"}}`

const xxtFakeCourseList = `{"result":1,"channelList":[{"cpi":1001,"key":2001,"content":{"name":"班级A","chatid":"chat1","isstart":true,"state":0,"course":{"data":[{"id":5001,"name":"课程A","imageurl":"http://img","teacherfactor":"teacher1","courseSquareUrl":"?courseId=9001&classId=2001&personId=1001&userId=7001"}]}}}]}`

func TestGetCoursesXuexitong_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtCourseList(xxtFakeCourseList, nil)
	defer restore()

	e := parseEnvelope(t, GetCourses(xxtSessJSON))
	if !e.OK {
		t.Fatalf("GetCourses failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res CourseListResult
	json.Unmarshal(b, &res)
	if len(res.Courses) != 1 {
		t.Fatalf("expected 1 course, got %d", len(res.Courses))
	}
	if res.Courses[0].Name != "课程A" {
		t.Fatalf("name=%q", res.Courses[0].Name)
	}
	raw := res.Courses[0].Raw
	for _, f := range []string{"courseId", "classId", "cpi", "chatId"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("course Raw missing %q", f)
		}
	}
}

func TestGetCoursesXuexitong_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtCourseList("", errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, GetCourses(xxtSessJSON))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

func TestGetCoursesXuexitong_ParsesResultField(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtCourseList(`{"result":0,"channelList":[]}`, nil)
	defer restore()
	e := parseEnvelope(t, GetCourses(xxtSessJSON))
	if e.OK {
		t.Fatal("result=0 should fail")
	}
}

func TestStartLoginXuexitong_Done(t *testing.T) {
	// Verify StartLogin("xuexitong") goes through the real provider path (not blocked).
	resetState()
	Init("/tmp/test")
	restore := fakeXxtLogin(`{"status":true,"msg2":"","url":"http://i.mooc.chaoxing.com"}`, nil)
	defer restore()

	e := parseEnvelope(t, StartLogin("xuexitong", `{"account":"user","password":"pass"}`))
	if !e.OK {
		t.Fatalf("StartLogin xuexitong should succeed via provider: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res StartLoginResult
	json.Unmarshal(b, &res)
	if res.Status != LoginStatusDone || res.Session == nil {
		t.Fatalf("expected done session, got %+v", res)
	}
	if res.Session.Platform != "xuexitong" {
		t.Fatalf("platform=%q", res.Session.Platform)
	}
}

func TestLoginXuexitong_CookieMode(t *testing.T) {
	resetState()
	Init("/tmp/test")
	called := false
	orig := xxtLoginProvider
	xxtLoginProvider = func(_ *xxtmobile.XxtClient) (string, error) {
		called = true
		return "", errors.New("should not call provider")
	}
	defer func() { xxtLoginProvider = orig }()

	cookie := "fid=1; UID=2; vc3=" + strings.Repeat("x", 80)
	e := parseEnvelope(t, Login("xuexitong", `{"account":"user","password":"`+cookie+`"}`))
	if !e.OK {
		t.Fatalf("cookie login failed: %s", e.Error)
	}
	if called {
		t.Fatal("cookie mode should not call HTTP login provider")
	}
	b, _ := json.Marshal(e.Data)
	var sess SessionData
	json.Unmarshal(b, &sess)
	if sess.Cookies != cookie || sess.Extra["loginMode"] != "cookie" {
		t.Fatalf("unexpected session: %+v", sess)
	}
}
