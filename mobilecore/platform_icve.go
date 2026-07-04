package mobilecore

import (
	"encoding/json"
	"fmt"

	icveApi "github.com/yatori-dev/yatori-go-mobile-core/api/icve"
)

// icveCache reconstructs an IcveUserCache from a SessionData.
func icveCache(sess SessionData) *icveApi.IcveUserCache {
	zykToken := strOf(sess.Extra["zykAccessToken"])
	userId := strOf(sess.Extra["userId"])
	cookies := icveApi.ParseCookieString(sess.Cookies)
	return &icveApi.IcveUserCache{
		Token:          sess.Token,
		ZYKAccessToken: zykToken,
		UserId:         userId,
		Cookies:        cookies,
	}
}

// icveProviders – replaceable for tests.

var icveZYKTokenProvider = func(c *icveApi.IcveUserCache) (string, error) {
	return c.ZYKAccessTokenApi()
}

var icveUserInfoProvider = func(c *icveApi.IcveUserCache) (string, error) {
	return c.ZYKPullUserInfoApi()
}

var icveCourseListProvider = func(c *icveApi.IcveUserCache) (string, error) {
	return c.ZYKCourseListApi()
}

var icveRootNodeProvider = func(c *icveApi.IcveUserCache, courseInfoId string) (string, error) {
	return c.ZYKRootNodeListApi(courseInfoId)
}

var icveNodeListProvider = func(c *icveApi.IcveUserCache, level int, parentId, courseInfoId string) (string, error) {
	return c.ZYKNodeListApi(level, parentId, courseInfoId, 5, nil)
}

var icveNodeInfoProvider = func(c *icveApi.IcveUserCache, id string) (string, error) {
	return c.ZYKNodeInfoApi(id)
}

var icveSubmitStudyProvider = func(c *icveApi.IcveUserCache, courseInfoId, parentId string, studyTime int, sourceId, studentId string) (string, error) {
	return c.ZYKSubmitStudyTimeApi(courseInfoId, parentId, studyTime, sourceId, studentId, 5, nil)
}

// --- Login ---

// loginIcve authenticates via cookie string (password field holds cookie). The Tencent
// image-CAPTCHA flow used by username/password login requires ddddocr (banned in
// gomobile), so cookie login is the only supported method here.
func loginIcve(input AccountInput) (SessionData, error) {
	cookies := icveApi.ParseCookieString(input.Password)
	if len(cookies) == 0 {
		return SessionData{}, fmt.Errorf("icve: password must be a cookie string (name=value; ...)")
	}
	cache := &icveApi.IcveUserCache{Cookies: cookies}
	// Extract `token` from cookies → needed for ZYK token exchange.
	for _, ck := range cookies {
		if ck.Name == "token" {
			cache.Token = ck.Value
		}
	}
	if cache.Token == "" {
		return SessionData{}, fmt.Errorf("icve: cookie string must contain token=<value>")
	}
	// Exchange SSO token for ZYK (资源库) JWT.
	zykRaw, err := icveZYKTokenProvider(cache)
	if err != nil {
		return SessionData{}, fmt.Errorf("icve: ZYK token exchange failed: %w", err)
	}
	var zykResp struct {
		Code int `json:"code"`
		Data struct {
			AccessToken string `json:"access_token"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(zykRaw), &zykResp); err != nil || zykResp.Code != 200 || zykResp.Data.AccessToken == "" {
		return SessionData{}, fmt.Errorf("icve: ZYK token exchange failed: %s", zykRaw)
	}
	cache.ZYKAccessToken = zykResp.Data.AccessToken

	// Pull user profile for userId.
	infoRaw, err := icveUserInfoProvider(cache)
	if err != nil {
		return SessionData{}, fmt.Errorf("icve: user info failed: %w", err)
	}
	var infoResp struct {
		Code int                    `json:"code"`
		User map[string]interface{} `json:"user"`
	}
	if err := json.Unmarshal([]byte(infoRaw), &infoResp); err != nil {
		return SessionData{}, fmt.Errorf("icve: user info parse failed: %w", err)
	}
	userId := ""
	if u, ok := infoResp.User["userId"].(string); ok {
		userId = u
	}

	cookieStr := cookieSliceToStr(cookies)
	return SessionData{
		Platform: "icve",
		Account:  input.Account,
		Token:    cache.Token,
		Cookies:  cookieStr,
		Extra: map[string]interface{}{
			"zykAccessToken": cache.ZYKAccessToken,
			"userId":         userId,
		},
	}, nil
}

// --- GetCourses ---

func getCoursesIcve(sess SessionData) (CourseListResult, error) {
	c := icveCache(sess)
	raw, err := icveCourseListProvider(c)
	if err != nil {
		return CourseListResult{}, fmt.Errorf("icve: course list failed: %w", err)
	}
	var resp struct {
		Code int `json:"code"`
		Rows []struct {
			Id           string `json:"id"`
			CourseId     string `json:"courseId"`
			CourseName   string `json:"courseName"`
			SchoolName   string `json:"schoolName"`
			CourseInfoId string `json:"courseInfoId"`
			Status       string `json:"status"`
		} `json:"rows"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseListResult{}, fmt.Errorf("icve: course list parse error: %w", err)
	}
	if resp.Code != 200 {
		return CourseListResult{}, fmt.Errorf("icve: course list failed (code=%d): %s", resp.Code, raw)
	}
	items := make([]CourseItem, 0, len(resp.Rows))
	for _, r := range resp.Rows {
		items = append(items, CourseItem{
			ID:   r.CourseInfoId,
			Name: r.CourseName,
			Raw: map[string]interface{}{
				"courseInfoId": r.CourseInfoId,
				"courseId":     r.CourseId,
				"rowId":        r.Id,
				"schoolName":   r.SchoolName,
				"status":       r.Status,
			},
		})
	}
	return CourseListResult{Platform: "icve", Courses: items}, nil
}

// --- GetCourseDetail ---
// ICVE has no separate course-detail API; forward-pass the course item.
func getCourseDetailIcve(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseInfoId := input.ID
	if courseInfoId == "" {
		courseInfoId = strOf(input.Raw["courseInfoId"])
	}
	if courseInfoId == "" {
		return CourseDetailResult{}, fmt.Errorf("icve: courseJSON.id (or raw.courseInfoId) is required")
	}
	raw := map[string]interface{}{"courseInfoId": courseInfoId}
	for k, v := range input.Raw {
		if _, ok := raw[k]; !ok {
			raw[k] = v
		}
	}
	item := CourseItem{ID: courseInfoId, Name: strOf(input.Raw["name"]), Raw: raw}
	return CourseDetailResult{Platform: "icve", ParentID: courseInfoId, Items: []CourseItem{item}}, nil
}

// --- GetTasks ---

// icveLeafTypes: file types that represent leaf task nodes (not containers).
var icveLeafTypes = map[string]bool{
	"mp4": true, "mp3": true, "pdf": true, "doc": true, "docx": true,
	"ppt": true, "pptx": true, "zip": true, "测验": true,
}

func getTasksIcve(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseInfoId := input.ID
	if courseInfoId == "" {
		courseInfoId = strOf(input.Raw["courseInfoId"])
	}
	if courseInfoId == "" {
		return TaskListResult{}, fmt.Errorf("icve: courseJSON.id (or raw.courseInfoId) is required")
	}
	c := icveCache(sess)
	rootRaw, err := icveRootNodeProvider(c, courseInfoId)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("icve: root node list failed: %w", err)
	}
	var rootNodes []map[string]interface{}
	if err := json.Unmarshal([]byte(rootRaw), &rootNodes); err != nil {
		return TaskListResult{}, fmt.Errorf("icve: root node parse error: %w", err)
	}
	tasks := make([]TaskItem, 0)
	for _, node := range rootNodes {
		expanded, err := icveExpandNode(c, node, 1, courseInfoId)
		if err != nil {
			continue
		}
		tasks = append(tasks, expanded...)
	}
	return TaskListResult{Platform: "icve", ParentID: courseInfoId, Tasks: tasks}, nil
}

// icveExpandNode recursively expands a node. Leaf nodes become TaskItems.
func icveExpandNode(c *icveApi.IcveUserCache, node map[string]interface{}, level int, courseInfoId string) ([]TaskItem, error) {
	fileType, _ := node["fileType"].(string)
	id, _ := node["id"].(string)
	parentId, _ := node["parentId"].(string)
	name, _ := node["name"].(string)
	if id == "" {
		return nil, nil
	}
	if icveLeafTypes[fileType] {
		speed := 0.0
		if sr, ok := node["studentStudyRecord"].(map[string]interface{}); ok {
			if sp, ok := sr["speed"].(float64); ok {
				speed = sp
			}
		}
		status := "pending"
		if speed >= 100 {
			status = "completed"
		}
		return []TaskItem{{
			ID:       id,
			Name:     name,
			Type:     icveTaskType(fileType),
			Status:   status,
			Progress: speed,
			Raw: map[string]interface{}{
				"courseInfoId": courseInfoId,
				"parentId":     parentId,
				"fileType":     fileType,
				"speed":        speed,
			},
		}}, nil
	}
	// Container node — recurse.
	childLevel := 1
	if fileType == "子节点" {
		childLevel = level + 1
	}
	childRaw, err := icveNodeListProvider(c, childLevel, id, courseInfoId)
	if err != nil {
		return nil, nil
	}
	var children []map[string]interface{}
	if err := json.Unmarshal([]byte(childRaw), &children); err != nil {
		return nil, nil
	}
	var result []TaskItem
	for _, child := range children {
		expanded, err := icveExpandNode(c, child, childLevel, courseInfoId)
		if err != nil {
			continue
		}
		result = append(result, expanded...)
	}
	return result, nil
}

func icveTaskType(fileType string) string {
	switch fileType {
	case "mp4":
		return "video"
	case "mp3":
		return "audio"
	case "pdf", "doc", "docx", "ppt", "pptx", "zip":
		return "document"
	case "测验":
		return "quiz"
	default:
		return "other"
	}
}

// --- RunTask ---

// runTaskIcve submits study completion for a single ICVE task node.
// Mobile-core only supports one-shot completion (mirrors console SubmitZYKStudyTimeAction).
// options.action="getInfo" returns raw.totalNum without submitting.
func runTaskIcve(sess SessionData, input TaskInput) (RunTaskResult, error) {
	nodeId := input.ID
	if nodeId == "" {
		nodeId = strOf(input.Raw["nodeId"])
	}
	if nodeId == "" {
		return RunTaskResult{}, fmt.Errorf("icve: taskJSON.id is required")
	}
	courseInfoId := strOf(input.Raw["courseInfoId"])
	if courseInfoId == "" {
		return RunTaskResult{}, fmt.Errorf("icve: taskJSON.raw.courseInfoId is required")
	}
	parentId := strOf(input.Raw["parentId"])

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "icve", TaskID: nodeId, Status: "dry_run"}, nil
	}

	c := icveCache(sess)

	// Fetch node info to determine totalNum (duration/page count).
	infoRaw, err := icveNodeInfoProvider(c, nodeId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("icve: node info failed: %w", err)
	}
	totalNum := 0
	var infoResp struct {
		Code int `json:"code"`
		Data struct {
			UrlShort string  `json:"urlShort"`
			FileUrl  string  `json:"fileUrl"`
			TotalNum float64 `json:"totalNum"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(infoRaw), &infoResp); err == nil && infoResp.Code == 200 {
		totalNum = int(infoResp.Data.TotalNum)
		// Fallback: some node types embed duration in a different field.
		if totalNum == 0 {
			if v, ok := parseJsonFloat(infoRaw, "data", "duration"); ok {
				totalNum = int(v)
			}
		}
	}
	if totalNum <= 0 {
		totalNum = 1
	}

	if strOf(input.Options["action"]) == "getInfo" {
		return RunTaskResult{
			Platform: "icve", TaskID: nodeId, Status: "done",
			Raw: map[string]interface{}{"totalNum": totalNum},
		}, nil
	}

	submitRaw, err := icveSubmitStudyProvider(c, courseInfoId, parentId, totalNum, nodeId, c.UserId)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("icve: submit study time failed: %w", err)
	}
	// Validate response (code 200 = success).
	var submitResp struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal([]byte(submitRaw), &submitResp); err == nil && submitResp.Code != 200 {
		return RunTaskResult{}, fmt.Errorf("icve: submit study time failed (code=%d): %s", submitResp.Code, submitRaw)
	}
	return RunTaskResult{
		Platform: "icve",
		TaskID:   nodeId,
		Status:   "submitted",
		Message:  fmt.Sprintf("totalNum=%d", totalNum),
		Raw:      map[string]interface{}{"totalNum": totalNum},
	}, nil
}

// parseJsonFloat is a minimal helper to extract a nested float from raw JSON.
func parseJsonFloat(raw string, keys ...string) (float64, bool) {
	var m interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return 0, false
	}
	for _, k := range keys {
		mm, ok := m.(map[string]interface{})
		if !ok {
			return 0, false
		}
		m = mm[k]
	}
	f, ok := m.(float64)
	return f, ok
}
