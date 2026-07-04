package mobilecore

// AccountInput is the JSON structure for the Login accountJSON parameter.
// {"account":"...","password":"...","url":"...","extra":{}}
type AccountInput struct {
	Account  string                 `json:"account"`
	Password string                 `json:"password"`
	URL      string                 `json:"url"`
	Extra    map[string]interface{} `json:"extra"`
}

// SessionData is returned by Login and passed back to GetCourses/GetTasks/etc.
// Token holds bearer/ASUSS tokens; Cookies holds serialised HTTP cookie strings.
// Extra holds platform-specific ids (userId, schoolId, …).
type SessionData struct {
	Platform string                 `json:"platform"`
	Account  string                 `json:"account"`
	Token    string                 `json:"token"`
	Cookies  string                 `json:"cookies,omitempty"`
	Extra    map[string]interface{} `json:"extra"`
}

// CourseItem is the common course DTO.
type CourseItem struct {
	ID       string                 `json:"id"`
	Name     string                 `json:"name"`
	Cover    string                 `json:"cover"`
	Progress float64                `json:"progress"`
	Raw      map[string]interface{} `json:"raw"`
}

// CourseListResult is returned by GetCourses.
type CourseListResult struct {
	Platform string       `json:"platform"`
	Courses  []CourseItem `json:"courses"`
}

// CourseInput is passed to GetCourseDetail — the caller echoes back a CourseItem.
// {"platform":"ttcdw","id":"<projectId>","raw":{"classId":"...","orgId":"..."}}
type CourseInput struct {
	Platform string                 `json:"platform"`
	ID       string                 `json:"id"`
	Raw      map[string]interface{} `json:"raw,omitempty"`
}

// TaskInput is passed to RunTask — caller can echo a TaskItem from GetTasks.
type TaskInput struct {
	Platform string                 `json:"platform"`
	ID       string                 `json:"id"`
	Type     string                 `json:"type,omitempty"`
	Raw      map[string]interface{} `json:"raw,omitempty"`
	Options  map[string]interface{} `json:"options,omitempty"`
}

// RunTaskResult is returned by RunTask.
// Raw carries host-driven-loop continuation metadata (e.g. serverRecordId,
// studyId, sessionId, progress, maxPos) so the Android host can drive pacing,
// mode and concurrency while mobile-core only exposes single-step primitives.
type RunTaskResult struct {
	Platform string                 `json:"platform"`
	TaskID   string                 `json:"taskId"`
	Status   string                 `json:"status"` // submitted | dry_run | done | started
	Message  string                 `json:"message,omitempty"`
	Raw      map[string]interface{} `json:"raw,omitempty"`
}
type TaskItem struct {
	ID       string                 `json:"id"`
	Name     string                 `json:"name"`
	Type     string                 `json:"type,omitempty"`     // video|document|exam|work
	Status   string                 `json:"status,omitempty"`   // not_started|done
	Progress float64                `json:"progress,omitempty"`
	Raw      map[string]interface{} `json:"raw,omitempty"`
}

// TaskListResult is returned by GetTasks.
type TaskListResult struct {
	Platform string     `json:"platform"`
	ParentID string     `json:"parentId"`
	Tasks    []TaskItem `json:"tasks"`
}

// CourseDetailResult is returned by GetCourseDetail.
type CourseDetailResult struct {
	Platform string       `json:"platform"`
	ParentID string       `json:"parentId"`
	Items    []CourseItem `json:"items"`
}
