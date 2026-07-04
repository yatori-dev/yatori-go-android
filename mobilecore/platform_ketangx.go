package mobilecore

import (
	"fmt"

	ketangxAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/ketangx"
	ketangxApi "github.com/yatori-dev/yatori-go-mobile-core/api/ketangx"
)

func loginKetangx(input AccountInput) (SessionData, error) {
	cache := &ketangxApi.KetangxUserCache{
		Account:  input.Account,
		Password: input.Password,
	}
	if err := ketangxAgg.LoginAction(cache); err != nil {
		return SessionData{}, err
	}
	return SessionData{
		Platform: "ketangx",
		Account:  input.Account,
		Cookies:  cookieSliceToStr(cache.Cookies),
		Extra: map[string]interface{}{
			"userId":   cache.UserId,
			"id":       cache.Id,
			"userName": cache.UserName,
		},
	}, nil
}

func ketangxCache(sess SessionData) *ketangxApi.KetangxUserCache {
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	return &ketangxApi.KetangxUserCache{
		Account:  sess.Account,
		Cookies:  cookieStrToSlice(cookieStr),
		UserId:   strOf(sess.Extra["userId"]),
		Id:       strOf(sess.Extra["id"]),
		UserName: strOf(sess.Extra["userName"]),
	}
}

// --- replaceable providers (test seams) ---

var ketangxCourseListProvider = func(cache *ketangxApi.KetangxUserCache) []ketangxAgg.KetangxCourse {
	return ketangxAgg.PullCourseListAction(cache)
}

var ketangxNodeProvider = func(cache *ketangxApi.KetangxUserCache, courseId string) ([]ketangxAgg.KetangxNode, error) {
	nodes := ketangxAgg.PullNodeListAction(cache, &ketangxAgg.KetangxCourse{ActivityId: courseId})
	return nodes, nil
}

var ketangxSignProvider = func(cache *ketangxApi.KetangxUserCache, sectId string) (string, error) {
	return cache.SignVideoStatusApi(sectId)
}

var ketangxCompleteProvider = func(cache *ketangxApi.KetangxUserCache, sectId, userId string, studyTime, duration int) (string, error) {
	return cache.CompleteVideoApi(sectId, userId, studyTime, duration)
}

func getCoursesKetangx(sess SessionData) (CourseListResult, error) {
	cache := ketangxCache(sess)
	userId := strOf(sess.Extra["userId"])
	id := strOf(sess.Extra["id"])
	userName := strOf(sess.Extra["userName"])
	courses := ketangxCourseListProvider(cache)
	items := make([]CourseItem, 0, len(courses))
	for _, c := range courses {
		items = append(items, CourseItem{
			ID:       c.ActivityId,
			Name:     c.Title,
			Progress: c.Progress,
			Raw: map[string]interface{}{
				"activityId": c.ActivityId,
				"courseId":   c.ActivityId,
				"title":      c.Title,
				"progress":   c.Progress,
				"userId":     userId,
				"id":         id,
				"userName":   userName,
			},
		})
	}
	return CourseListResult{Platform: "ketangx", Courses: items}, nil
}

// getCourseDetailKetangx is a normalized field-forwarding wrapper (no separate detail API).
func getCourseDetailKetangx(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		courseId = strOf(input.Raw["activityId"])
	}
	if courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("ketangx: courseJSON.id (or raw.courseId/activityId) is required")
	}
	raw := map[string]interface{}{
		"courseId":   courseId,
		"activityId": courseId,
		"title":      strOf(input.Raw["title"]),
		"userId":     strOf(input.Raw["userId"]),
		"id":         strOf(input.Raw["id"]),
		"userName":   strOf(input.Raw["userName"]),
	}
	for k, v := range input.Raw {
		if _, exists := raw[k]; !exists {
			raw[k] = v
		}
	}
	name := strOf(input.Raw["title"])
	item := CourseItem{ID: courseId, Name: name, Raw: raw}
	return CourseDetailResult{Platform: "ketangx", ParentID: courseId, Items: []CourseItem{item}}, nil
}

func ketangxNodeType(t string) string {
	switch t {
	case "视频":
		return "video"
	case "文档":
		return "document"
	default:
		return t
	}
}

func getTasksKetangx(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		courseId = strOf(input.Raw["activityId"])
	}
	if courseId == "" {
		return TaskListResult{}, fmt.Errorf("ketangx: courseJSON.id (or raw.courseId/activityId) is required")
	}
	userId := strOf(input.Raw["userId"])
	if userId == "" {
		userId = strOf(sess.Extra["userId"])
	}
	id := strOf(input.Raw["id"])
	if id == "" {
		id = strOf(sess.Extra["id"])
	}
	userName := strOf(input.Raw["userName"])
	if userName == "" {
		userName = strOf(sess.Extra["userName"])
	}

	cache := ketangxCache(sess)
	nodes, err := ketangxNodeProvider(cache, courseId)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("ketangx: tasks request failed: %w", err)
	}
	tasks := make([]TaskItem, 0, len(nodes))
	for _, n := range nodes {
		status := "not_started"
		if n.IsComplete {
			status = "done"
		}
		tasks = append(tasks, TaskItem{
			ID:     n.SectId,
			Name:   n.Title,
			Type:   ketangxNodeType(n.Type),
			Status: status,
			Raw: map[string]interface{}{
				"sectId":     n.SectId,
				"courseId":   courseId,
				"activityId": courseId,
				"title":      n.Title,
				"enterNum":   n.EnterNum,
				"isComplete": n.IsComplete,
				"type":       n.Type,
				"userId":     userId,
				"id":         id,
				"userName":   userName,
			},
		})
	}
	return TaskListResult{Platform: "ketangx", ParentID: courseId, Tasks: tasks}, nil
}

func runTaskKetangx(sess SessionData, input TaskInput) (RunTaskResult, error) {
	sectId := input.ID
	if sectId == "" {
		sectId = strOf(input.Raw["sectId"])
	}
	if sectId == "" {
		sectId = strOf(input.Raw["id"])
	}
	if sectId == "" {
		return RunTaskResult{}, fmt.Errorf("ketangx: taskJSON.id (or raw.sectId) is required")
	}
	userId := strOf(input.Raw["userId"])
	if userId == "" {
		userId = strOf(sess.Extra["userId"])
	}
	if userId == "" {
		userId = strOf(sess.Extra["id"])
	}
	if userId == "" {
		return RunTaskResult{}, fmt.Errorf("ketangx: taskJSON.raw.userId is required")
	}

	studyTime := 114514
	if v, ok := input.Options["studyTime"]; ok {
		if f, ok := v.(float64); ok {
			studyTime = int(f)
		}
	}
	duration := studyTime
	if v, ok := input.Options["duration"]; ok {
		if f, ok := v.(float64); ok {
			duration = int(f)
		}
	}

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "ketangx", TaskID: sectId, Status: "dry_run"}, nil
	}

	cache := ketangxCache(sess)
	if _, err := ketangxSignProvider(cache, sectId); err != nil {
		return RunTaskResult{}, fmt.Errorf("ketangx: sign video status failed: %w", err)
	}
	if _, err := ketangxCompleteProvider(cache, sectId, userId, studyTime, duration); err != nil {
		return RunTaskResult{}, fmt.Errorf("ketangx: complete video failed: %w", err)
	}
	return RunTaskResult{
		Platform: "ketangx",
		TaskID:   sectId,
		Status:   "submitted",
		Message:  fmt.Sprintf("studyTime=%d duration=%d", studyTime, duration),
	}, nil
}
