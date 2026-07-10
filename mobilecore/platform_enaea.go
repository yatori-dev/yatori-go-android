package mobilecore

import (
	"fmt"
	"time"

	enaeaAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/enaea"
	enaeaApi "github.com/yatori-dev/yatori-go-mobile-core/api/enaea"
)

func loginEnaea(input AccountInput) (SessionData, error) {
	cache := &enaeaApi.EnaeaUserCache{
		Account:  input.Account,
		Password: input.Password,
	}
	if _, err := enaeaAgg.EnaeaLoginAction(cache); err != nil {
		return SessionData{}, err
	}
	return SessionData{
		Platform: "enaea",
		Account:  input.Account,
		Token:    cache.Asuss,
		Extra:    map[string]interface{}{},
	}, nil
}

func enaeaCache(sess SessionData) *enaeaApi.EnaeaUserCache {
	return &enaeaApi.EnaeaUserCache{Account: sess.Account, Asuss: sess.Token}
}

// --- replaceable providers (test seams) ---

var enaeaAllCoursesProvider = func(cache *enaeaApi.EnaeaUserCache) ([]enaeaAgg.EnaeaCourse, error) {
	projects, err := enaeaAgg.ProjectListAction(cache)
	if err != nil {
		return nil, err
	}
	return loadEnaeaCourses(projects, func(circleId string) ([]enaeaAgg.EnaeaCourse, error) {
		return enaeaAgg.CourseListAction(cache, circleId)
	})
}

func loadEnaeaCourses(
	projects []enaeaAgg.EnaeaProject,
	load func(circleId string) ([]enaeaAgg.EnaeaCourse, error),
) ([]enaeaAgg.EnaeaCourse, error) {
	var all []enaeaAgg.EnaeaCourse
	for _, p := range projects {
		courses, err := load(p.CircleId)
		if err != nil {
			return nil, fmt.Errorf("enaea: project %q courses request failed: %w", p.ClusterName, err)
		}
		for i := range courses {
			courses[i].ProjectName = p.ClusterName
		}
		all = append(all, courses...)
	}
	return all, nil
}

var enaeaVideoProvider = func(cache *enaeaApi.EnaeaUserCache, courseId, circleId, contentType, externalType, remark, titleTag string) ([]enaeaAgg.EnaeaVideo, error) {
	course := &enaeaAgg.EnaeaCourse{
		CourseId: courseId, CircleId: circleId,
		CourseContentType: contentType, CourseExternalType: externalType,
		Remark: remark, TitleTag: titleTag,
	}
	return enaeaAgg.VideoListAction(cache, course)
}

var enaeaStatisticProvider = func(cache *enaeaApi.EnaeaUserCache, courseId, videoId, circleId string) (string, string, error) {
	video := &enaeaAgg.EnaeaVideo{CourseId: courseId, Id: videoId, CircleId: circleId}
	if err := enaeaAgg.StatisticTicForCCVideAction(cache, video); err != nil {
		return "", "", err
	}
	return video.SCFUCKPKey, video.SCFUCKPValue, nil
}

// enaeaSubmitProvider submits one study tick and returns the server-written
// progress (0..100) so the host can drive the 25s loop and stop at 100.
var enaeaSubmitProvider = func(cache *enaeaApi.EnaeaUserCache, circleId, key, value, videoId string, studyTime int64, fast bool) (float64, error) {
	video := &enaeaAgg.EnaeaVideo{CircleId: circleId, SCFUCKPKey: key, SCFUCKPValue: value, Id: videoId}
	model := int64(0)
	if fast {
		model = 1
	}
	if err := enaeaAgg.SubmitStudyTimeAction(cache, video, studyTime, model); err != nil {
		return 0, err
	}
	return float64(video.StudyProgress), nil
}

func getCoursesEnaea(sess SessionData) (CourseListResult, error) {
	cache := enaeaCache(sess)
	courses, err := enaeaAllCoursesProvider(cache)
	if err != nil {
		return CourseListResult{}, err
	}
	items := make([]CourseItem, 0, len(courses))
	for _, c := range courses {
		items = append(items, CourseItem{
			ID:       c.CourseId,
			Name:     c.CourseTitle,
			Progress: float64(c.StudyProgress),
			Raw: map[string]interface{}{
				"projectName":        c.ProjectName,
				"titleTag":           c.TitleTag,
				"courseId":           c.CourseId,
				"circleId":           c.CircleId,
				"syllabusId":         c.SyllabusId,
				"remark":             c.Remark,
				"courseContentType":  c.CourseContentType,
				"courseExternalType": c.CourseExternalType,
				"courseContentLink":  c.CourseContentLink,
			},
		})
	}
	return CourseListResult{Platform: "enaea", Courses: items}, nil
}

// getCourseDetailEnaea is a normalized field-forwarding wrapper (enaea has no separate detail API).
func getCourseDetailEnaea(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("enaea: courseJSON.id (or raw.courseId) is required")
	}
	circleId := strOf(input.Raw["circleId"])
	if circleId == "" {
		return CourseDetailResult{}, fmt.Errorf("enaea: courseJSON.raw.circleId is required")
	}
	raw := map[string]interface{}{
		"courseId":           courseId,
		"circleId":           circleId,
		"courseContentType":  strOf(input.Raw["courseContentType"]),
		"courseExternalType": strOf(input.Raw["courseExternalType"]),
		"remark":             strOf(input.Raw["remark"]),
		"courseContentLink":  strOf(input.Raw["courseContentLink"]),
		"syllabusId":         strOf(input.Raw["syllabusId"]),
		"titleTag":           strOf(input.Raw["titleTag"]),
	}
	for k, v := range input.Raw {
		if _, exists := raw[k]; !exists {
			raw[k] = v
		}
	}
	item := CourseItem{ID: courseId, Name: strOf(input.Raw["remark"]), Raw: raw}
	return CourseDetailResult{Platform: "enaea", ParentID: courseId, Items: []CourseItem{item}}, nil
}

func getTasksEnaea(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return TaskListResult{}, fmt.Errorf("enaea: courseJSON.id (or raw.courseId) is required")
	}
	circleId := strOf(input.Raw["circleId"])
	if circleId == "" {
		return TaskListResult{}, fmt.Errorf("enaea: courseJSON.raw.circleId is required")
	}
	cache := enaeaCache(sess)
	videos, err := enaeaVideoProvider(cache,
		courseId, circleId,
		strOf(input.Raw["courseContentType"]),
		strOf(input.Raw["courseExternalType"]),
		strOf(input.Raw["remark"]),
		strOf(input.Raw["titleTag"]),
	)
	if err != nil {
		return TaskListResult{}, fmt.Errorf("enaea: tasks request failed: %w", err)
	}
	tasks := make([]TaskItem, 0, len(videos))
	for _, v := range videos {
		name := v.CourseContentStr
		if name == "" {
			name = v.CourseName
		}
		status := "not_started"
		if v.StudyProgress >= 100 {
			status = "done"
		} else if v.StudyProgress > 0 {
			status = "in_progress"
		}
		tasks = append(tasks, TaskItem{
			ID:       v.Id,
			Name:     name,
			Type:     "video",
			Progress: float64(v.StudyProgress),
			Status:   status,
			Raw: map[string]interface{}{
				"videoId":          v.Id,
				"courseId":         v.CourseId,
				"circleId":         v.CircleId,
				"videoLength":      v.VideoLength,
				"courseContentStr": v.CourseContentStr,
				"courseName":       v.CourseName,
				"studyProgress":    v.StudyProgress,
				"SCFUCKPKey":       v.SCFUCKPKey,
				"SCFUCKPValue":     v.SCFUCKPValue,
			},
		})
	}
	return TaskListResult{Platform: "enaea", ParentID: courseId, Tasks: tasks}, nil
}

func runTaskEnaea(sess SessionData, input TaskInput) (RunTaskResult, error) {
	videoId := input.ID
	if videoId == "" {
		videoId = strOf(input.Raw["videoId"])
	}
	if videoId == "" {
		videoId = strOf(input.Raw["id"])
	}
	if videoId == "" {
		return RunTaskResult{}, fmt.Errorf("enaea: taskJSON.id (or raw.videoId) is required")
	}
	courseId := strOf(input.Raw["courseId"])
	if courseId == "" {
		return RunTaskResult{}, fmt.Errorf("enaea: taskJSON.raw.courseId is required")
	}
	circleId := strOf(input.Raw["circleId"])
	if circleId == "" {
		return RunTaskResult{}, fmt.Errorf("enaea: taskJSON.raw.circleId is required")
	}
	fast, _ := input.Options["fast"].(bool)
	mode := "normal"
	if fast {
		mode = "fast"
	}

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{Platform: "enaea", TaskID: videoId, Status: "dry_run", Message: "mode=" + mode}, nil
	}

	studyTime := int64(0)
	if v, ok := input.Options["studyTime"]; ok {
		if f, ok := v.(float64); ok {
			studyTime = int64(f)
		}
	}
	if studyTime == 0 {
		if fast {
			studyTime = 60
		} else {
			studyTime = time.Now().UnixMilli()
		}
	}

	key := strOf(input.Raw["SCFUCKPKey"])
	value := strOf(input.Raw["SCFUCKPValue"])
	if key == "" {
		key = strOf(input.Options["SCFUCKPKey"])
	}
	if value == "" {
		value = strOf(input.Options["SCFUCKPValue"])
	}
	cache := enaeaCache(sess)

	if key == "" || value == "" {
		k, v, err := enaeaStatisticProvider(cache, courseId, videoId, circleId)
		if err != nil {
			return RunTaskResult{}, fmt.Errorf("enaea: statistic ticket failed: %w", err)
		}
		key, value = k, v
	}

	progress, err := enaeaSubmitProvider(cache, circleId, key, value, videoId, studyTime, fast)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("enaea: submit study time failed: %w", err)
	}
	return RunTaskResult{Platform: "enaea", TaskID: videoId, Status: "submitted", Message: "mode=" + mode,
		Raw: map[string]interface{}{
			"progress":     progress,
			"SCFUCKPKey":   key,
			"SCFUCKPValue": value,
		}}, nil
}
