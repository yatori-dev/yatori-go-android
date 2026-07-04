package mobilecore

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// dispatchLogin routes Login to the correct platform adapter.
func dispatchLogin(platform string, input AccountInput) (SessionData, error) {
	switch platform {
	case "haiqikeji":
		return loginHaiqikeji(input)
	case "ketangx":
		return loginKetangx(input)
	case "ttcdw":
		return loginTtcdw(input)
	case "enaea":
		return loginEnaea(input)
	case "welearn":
		return loginWelearn(input)
	case "xuexitong":
		return loginXuexitong(input)
	case "icve":
		return loginIcve(input)
	case "weiban", "qingshuxuetang", "mooc", "cela", "gongxue":
		return SessionData{}, fmt.Errorf("platform %q: %s", platform, blockedReason(platform))
	case "yinghua":
		return SessionData{}, fmt.Errorf("platform %q: login requires captcha; use StartLogin", platform)
	default:
		return SessionData{}, fmt.Errorf("platform %q: unsupported platform", platform)
	}
}

// dispatchGetCourses routes GetCourses to the correct platform adapter.
func dispatchGetCourses(sess SessionData) (CourseListResult, error) {
	switch sess.Platform {
	case "haiqikeji":
		return getCoursesHaiqikeji(sess)
	case "ketangx":
		return getCoursesKetangx(sess)
	case "ttcdw":
		return getCoursesTtcdw(sess)
	case "enaea":
		return getCoursesEnaea(sess)
	case "welearn":
		return getCoursesWelearn(sess)
	case "qingshuxuetang":
		return getCoursesQsxt(sess)
	case "cqie":
		return getCoursesCqie(sess)
	case "xuexitong":
		return getCoursesXuexitong(sess)
	case "yinghua":
		return getCoursesYinghua(sess)
	case "icve":
		return getCoursesIcve(sess)
	case "weiban", "mooc", "cela", "gongxue":
		return CourseListResult{}, fmt.Errorf("platform %q: %s", sess.Platform, blockedReason(sess.Platform))
	default:
		return CourseListResult{}, fmt.Errorf("platform %q: unsupported platform", sess.Platform)
	}
}

// dispatchGetTasks returns the task/node list for a given course.
func dispatchGetTasks(sess SessionData, input CourseInput) (TaskListResult, error) {
	switch input.Platform {
	case "haiqikeji":
		return getTasksHaiqikeji(sess, input)
	case "ttcdw":
		return getTasksTtcdw(sess, input)
	case "qingshuxuetang":
		return getTasksQsxt(sess, input)
	case "welearn":
		return getTasksWelearn(sess, input)
	case "enaea":
		return getTasksEnaea(sess, input)
	case "ketangx":
		return getTasksKetangx(sess, input)
	case "cqie":
		return getTasksCqie(sess, input)
	case "xuexitong":
		return getTasksXuexitong(sess, input)
	case "yinghua":
		return getTasksYinghua(sess, input)
	case "icve":
		return getTasksIcve(sess, input)
	default:
		return TaskListResult{}, fmt.Errorf("platform %q: GetTasks not implemented", input.Platform)
	}
}
// Currently only ttcdw is implemented (project → classroom list).
func dispatchGetCourseDetail(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	switch input.Platform {
	case "haiqikeji":
		return getCourseDetailHaiqikeji(sess, input)
	case "ttcdw":
		return getCourseDetailTtcdw(sess, input)
	case "qingshuxuetang":
		return getCourseDetailQsxt(sess, input)
	case "welearn":
		return getCourseDetailWelearn(sess, input)
	case "enaea":
		return getCourseDetailEnaea(sess, input)
	case "ketangx":
		return getCourseDetailKetangx(sess, input)
	case "cqie":
		return getCourseDetailCqie(sess, input)
	case "xuexitong":
		return getCourseDetailXuexitong(sess, input)
	case "yinghua":
		return getCourseDetailYinghua(sess, input)
	case "icve":
		return getCourseDetailIcve(sess, input)
	default:
		return CourseDetailResult{}, fmt.Errorf("platform %q: GetCourseDetail not implemented", input.Platform)
	}
}

func dispatchRunTask(sess SessionData, input TaskInput) (RunTaskResult, error) {
	switch input.Platform {
	case "haiqikeji":
		return runTaskHaiqikeji(sess, input)
	case "qingshuxuetang":
		return runTaskQsxt(sess, input)
	case "ttcdw":
		return runTaskTtcdw(sess, input)
	case "welearn":
		return runTaskWelearn(sess, input)
	case "enaea":
		return runTaskEnaea(sess, input)
	case "ketangx":
		return runTaskKetangx(sess, input)
	case "cqie":
		return runTaskCqie(sess, input)
	case "xuexitong":
		return runTaskXuexitong(sess, input)
	case "yinghua":
		return runTaskYinghua(sess, input)
	case "icve":
		return runTaskIcve(sess, input)
	default:
		return RunTaskResult{}, fmt.Errorf("platform %q: RunTask not implemented", input.Platform)
	}
}

// strOf safely extracts a string from map[string]interface{}.
func strOf(v interface{}) string {
	if v == nil {
		return ""
	}
	s, _ := v.(string)
	return s
}

// jStr extracts a string from a gojsonq Find result; returns ("", false) if nil/wrong type.
func jStr(v interface{}) (string, bool) {
	if v == nil {
		return "", false
	}
	s, ok := v.(string)
	return s, ok
}

// jFloat extracts a float64 from a gojsonq Find result.
func jFloat(v interface{}) (float64, bool) {
	if v == nil {
		return 0, false
	}
	f, ok := v.(float64)
	return f, ok
}

// optInt extracts an int from an options/raw value that may be a JSON number
// (float64), an int, or a numeric string. Returns def when absent/unparseable.
func optInt(v interface{}, def int) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case string:
		if i, err := strconv.Atoi(n); err == nil {
			return i
		}
	}
	return def
}

// optBool extracts a bool from an options value that may be a bool or the
// strings "true"/"false". Returns def when absent/unparseable.
func optBool(v interface{}, def bool) bool {
	switch b := v.(type) {
	case bool:
		return b
	case string:
		if b == "true" {
			return true
		}
		if b == "false" {
			return false
		}
	}
	return def
}

// cookieSliceToStr serialises []*http.Cookie to "name=val; name2=val2".
func cookieSliceToStr(cookies []*http.Cookie) string {
	parts := make([]string, 0, len(cookies))
	for _, c := range cookies {
		parts = append(parts, c.Name+"="+c.Value)
	}
	return strings.Join(parts, "; ")
}

// cookieStrToSlice parses "name=val; name2=val2" into []*http.Cookie.
func cookieStrToSlice(s string) []*http.Cookie {
	var cookies []*http.Cookie
	for _, part := range strings.Split(s, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		kv := strings.SplitN(part, "=", 2)
		if len(kv) == 2 {
			cookies = append(cookies, &http.Cookie{Name: strings.TrimSpace(kv[0]), Value: kv[1]})
		}
	}
	return cookies
}
