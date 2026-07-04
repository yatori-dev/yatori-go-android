package mobilecore

import (
	"fmt"
	"strconv"
	"strings"
	"time"

	ttcdwAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/ttcdw"
	ttcdwApi "github.com/yatori-dev/yatori-go-mobile-core/api/ttcdw"
)

func loginTtcdw(input AccountInput) (SessionData, error) {
	cache := &ttcdwApi.TtcdwUserCache{
		Account:  input.Account,
		Password: input.Password,
	}
	if err := ttcdwAgg.TTCDWLoginAction(cache); err != nil {
		return SessionData{}, err
	}
	return SessionData{
		Platform: "ttcdw",
		Account:  input.Account,
		Cookies:  cookieSliceToStr(cache.Cookies),
		Extra:    map[string]interface{}{},
	}, nil
}

func getCoursesTtcdw(sess SessionData) (CourseListResult, error) {
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	projects, err := ttcdwAgg.PullProjectAction(cache)
	if err != nil {
		return CourseListResult{}, err
	}
	items := make([]CourseItem, 0, len(projects))
	for _, p := range projects {
		items = append(items, CourseItem{
			ID:   p.CourseProjectId,
			Name: p.Name,
			Raw:  map[string]interface{}{"classId": p.ClassId, "orgId": p.OrgId},
		})
	}
	return CourseListResult{Platform: "ttcdw", Courses: items}, nil
}

var ttcdwClassRoomProvider = func(cache *ttcdwApi.TtcdwUserCache, project ttcdwAgg.TtcdwProject) ([]ttcdwAgg.TtcdwClassRoom, error) {
	return ttcdwAgg.PullClassRoomAction(cache, project)
}

var ttcdwCourseProvider = func(cache *ttcdwApi.TtcdwUserCache, classroom ttcdwAgg.TtcdwClassRoom) ([]ttcdwAgg.TtcdwCourse, error) {
	return ttcdwAgg.PullCourseAction(cache, classroom)
}

var ttcdwVideoProvider = func(cache *ttcdwApi.TtcdwUserCache, project ttcdwAgg.TtcdwProject, classroom ttcdwAgg.TtcdwClassRoom, course ttcdwAgg.TtcdwCourse) ([]ttcdwAgg.TtcdwVideo, error) {
	return ttcdwAgg.PullVideoListAction(cache, project, classroom, course)
}

// getTasksTtcdw dispatches based on input.Raw:
// - if raw.courseId is present → expand course → video/lesson
// - otherwise → expand classroom → course
func getTasksTtcdw(sess SessionData, input CourseInput) (TaskListResult, error) {
	if strOf(input.Raw["courseId"]) != "" {
		return getVideosTtcdw(sess, input)
	}
	return getCoursesFromClassroomTtcdw(sess, input)
}

// getCoursesFromClassroomTtcdw expands a classroom into its course list.
// input.ID = classroom.ItemId; input.Raw["segmentId"] = classroom.SegmentId
// input.Raw["projectId"] and input.Raw["orgId"] come from GetCourseDetail.
func getCoursesFromClassroomTtcdw(sess SessionData, input CourseInput) (TaskListResult, error) {
	segmentId := strOf(input.Raw["segmentId"])
	if segmentId == "" {
		return TaskListResult{}, fmt.Errorf("ttcdw: courseJSON.raw.segmentId is required (from GetCourseDetail response)")
	}
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	classroom := ttcdwAgg.TtcdwClassRoom{
		ItemId:    input.ID,
		SegmentId: segmentId,
	}
	courses, err := ttcdwCourseProvider(cache, classroom)
	if err != nil {
		return TaskListResult{}, err
	}
	projectId := strOf(input.Raw["projectId"])
	orgId := strOf(input.Raw["orgId"])
	tasks := make([]TaskItem, 0, len(courses))
	for _, c := range courses {
		tasks = append(tasks, TaskItem{
			ID:       c.CourseId,
			Name:     c.Name,
			Type:     "course",
			Progress: float64(c.Progress),
			Raw: map[string]interface{}{
				"kind":          "course",
				"courseId":      c.CourseId,
				"itemId":        input.ID,  // classroom ItemId — needed for video expansion
				"segmentId":     segmentId, // classroom SegmentId
				"projectId":     projectId, // from GetCourseDetail classroom Raw
				"orgId":         orgId,     // from GetCourseDetail classroom Raw
				"originalId":    c.OriginalId,
				"shortCourseId": c.ShortCourseId,
				"md5":           c.MD5,
				"companyCode":   c.CompanyCode,
				"userId":        c.UserId,
			},
		})
	}
	return TaskListResult{Platform: "ttcdw", ParentID: input.ID, Tasks: tasks}, nil
}

// getVideosTtcdw expands a course into its video/lesson list.
// input.Raw must include: courseId, itemId, segmentId, projectId, orgId
func getVideosTtcdw(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseId := strOf(input.Raw["courseId"])
	itemId := strOf(input.Raw["itemId"])
	segmentId := strOf(input.Raw["segmentId"])
	projectId := strOf(input.Raw["projectId"])
	orgId := strOf(input.Raw["orgId"])
	for field, val := range map[string]string{
		"courseId":  courseId,
		"itemId":    itemId,
		"segmentId": segmentId,
		"projectId": projectId,
		"orgId":     orgId,
	} {
		if val == "" {
			return TaskListResult{}, fmt.Errorf("ttcdw: courseJSON.raw.%s is required for video expansion", field)
		}
	}
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	project := ttcdwAgg.TtcdwProject{CourseProjectId: projectId, OrgId: orgId}
	classroom := ttcdwAgg.TtcdwClassRoom{ItemId: itemId, SegmentId: segmentId}
	course := ttcdwAgg.TtcdwCourse{CourseId: courseId}
	videos, err := ttcdwVideoProvider(cache, project, classroom, course)
	if err != nil {
		return TaskListResult{}, err
	}
	tasks := make([]TaskItem, 0, len(videos))
	for _, v := range videos {
		tasks = append(tasks, TaskItem{
			ID:       v.VideoId,
			Name:     v.Name,
			Type:     ttcdwVideoType(v.CourseWareType),
			Progress: float64(v.Progress),
			Raw: map[string]interface{}{
				"videoId":        v.VideoId,
				"courseId":       courseId,
				"itemId":         itemId,
				"segmentId":      segmentId,
				"projectId":      projectId,
				"orgId":          orgId,
				"coursewareType": v.CourseWareType,
				"videoType":      v.VideoType,
				"duration":       v.Duration,
				"freeTime":       v.FreeTime,
				"token":          v.Token,
				"companyCode":    strOf(input.Raw["companyCode"]),
				"userId":         strOf(input.Raw["userId"]),
				"shortCourseId":  strOf(input.Raw["shortCourseId"]),
				"tickerCourseId": firstTtcdwString(strOf(input.Raw["tickerCourseId"]), strOf(input.Raw["shortCourseId"]), courseId),
				"courseType":     firstTtcdwString(strOf(input.Raw["courseType"]), "share"),
				"courseMd5":      strOf(input.Raw["md5"]),
			},
		})
	}
	return TaskListResult{Platform: "ttcdw", ParentID: courseId, Tasks: tasks}, nil
}

var ttcdwCourseParamProvider = func(cache *ttcdwApi.TtcdwUserCache, projectID, courseID string) (ttcdwApi.CourseParam, error) {
	raw, err := cache.PullCourseParamApi(projectID, courseID, 3, nil)
	if err != nil {
		return ttcdwApi.CourseParam{}, err
	}
	return ttcdwApi.ParseCourseParam(raw)
}

// ttcdwStudySubmitProvider submits current live-site progress. Replaceable for tests.
var ttcdwStudySubmitProvider = func(cache *ttcdwApi.TtcdwUserCache, opts ttcdwApi.StudyProgressSubmitOptions) (string, error) {
	return cache.StudyProgressSubmitApi(opts, 3, nil)
}

var ttcdwTickerSubmitProvider = func(cache *ttcdwApi.TtcdwUserCache, tickerURL, serverDataName, tickerData string) (string, error) {
	return cache.TickerSubmitApi(tickerURL, serverDataName, tickerData, 3, nil)
}

// runTaskTtcdw submits a single-step study progress for a ttcdw video/lesson task.
// taskInput.Raw must include: videoId, courseId, itemId, segmentId, orgId.
// Options: progress (default 100), finish (default true), dryRun (default false).
func runTaskTtcdw(sess SessionData, input TaskInput) (RunTaskResult, error) {
	action := strOf(input.Options["action"])
	submitMode := optString(input.Options["submitMode"], strOf(input.Raw["submitMode"]))
	switch {
	case action == "desPrepare" || (action == "prepare" && submitMode == "des"):
		return runTaskTtcdwDESPrepare(input)
	case action == "desTick" || action == "desTicker" || ((action == "tick" || action == "ticker") && submitMode == "des"):
		return runTaskTtcdwDESTick(sess, input)
	}
	switch action {
	case "prepare":
		return runTaskTtcdwPrepare(sess, input)
	case "tick", "ticker":
		return runTaskTtcdwTick(sess, input)
	}

	prepared, err := prepareTtcdwProgress(sess, input, 100, true)
	if err != nil {
		return RunTaskResult{}, err
	}
	if !optBool(input.Options["realSubmit"], false) {
		return RunTaskResult{
			Platform: "ttcdw", TaskID: input.ID, Status: "dry_run",
			Message: fmt.Sprintf("would submit videoId=%s progress=%d finish=%t", prepared.Options.VideoID, prepared.Options.PlayProgress, prepared.Options.IsFinish),
			Raw:     prepared.Raw,
		}, nil
	}
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	resp, err := ttcdwStudySubmitProvider(cache, prepared.Options)
	if err != nil {
		return RunTaskResult{}, err
	}
	ok, msg, err := ttcdwApi.ParseStudySubmitResult(resp)
	if err != nil {
		return RunTaskResult{}, err
	}
	if !ok {
		return RunTaskResult{}, fmt.Errorf("ttcdw: progress submit rejected: %s", msg)
	}
	prepared.Raw["realSubmit"] = true
	prepared.Raw["message"] = msg
	return RunTaskResult{Platform: "ttcdw", TaskID: input.ID, Status: "submitted", Message: msg, Raw: prepared.Raw}, nil
}

type ttcdwProgressPrepared struct {
	Options ttcdwApi.StudyProgressSubmitOptions
	Raw     map[string]interface{}
}

func runTaskTtcdwPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareTtcdwProgress(sess, input, 30, false)
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "ttcdw", TaskID: input.ID, Status: "prepared",
		Message: "progress ticker prepared; host should call action=tick every 30s",
		Raw:     prepared.Raw,
	}, nil
}

func runTaskTtcdwTick(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareTtcdwProgress(sess, input, 30, true)
	if err != nil {
		return RunTaskResult{}, err
	}
	prepared.Raw["realSubmit"] = false
	if !optBool(input.Options["realSubmit"], false) {
		return RunTaskResult{
			Platform: "ttcdw", TaskID: input.ID, Status: "dry_run",
			Message: "ttcdw progress ticker dry-run (set options.realSubmit=true to submit)",
			Raw:     prepared.Raw,
		}, nil
	}
	if !ttcdwProgressProvided(input) {
		return RunTaskResult{}, fmt.Errorf("ttcdw: action=tick realSubmit requires options.playProgress/progress/currentTime")
	}
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	resp, err := ttcdwStudySubmitProvider(cache, prepared.Options)
	if err != nil {
		return RunTaskResult{}, err
	}
	ok, msg, err := ttcdwApi.ParseStudySubmitResult(resp)
	if err != nil {
		return RunTaskResult{}, err
	}
	if !ok {
		return RunTaskResult{}, fmt.Errorf("ttcdw: progress submit rejected: %s", msg)
	}
	prepared.Raw["realSubmit"] = true
	prepared.Raw["message"] = msg
	return RunTaskResult{Platform: "ttcdw", TaskID: input.ID, Status: "submitted", Message: msg, Raw: prepared.Raw}, nil
}

func prepareTtcdwProgress(sess SessionData, input TaskInput, defaultProgress int, includeSubmitFields bool) (ttcdwProgressPrepared, error) {
	videoId := optString(input.Options["videoId"], strOf(input.Raw["videoId"]))
	courseId := optString(input.Options["courseId"], strOf(input.Raw["courseId"]))
	itemId := optString(input.Options["itemId"], strOf(input.Raw["itemId"]))
	segmentId := optString(input.Options["segmentId"], optString(input.Options["segId"], strOf(input.Raw["segmentId"])))
	orgId := optString(input.Options["orgId"], strOf(input.Raw["orgId"]))
	sourceId := optString(input.Options["sourceId"], firstTtcdwString(strOf(input.Raw["sourceId"]), strOf(input.Raw["projectId"])))
	for field, val := range map[string]string{
		"videoId": videoId, "courseId": courseId, "itemId": itemId,
		"segmentId": segmentId, "orgId": orgId, "projectId/sourceId": sourceId,
	} {
		if val == "" {
			return ttcdwProgressPrepared{}, fmt.Errorf("ttcdw: taskJSON.raw.%s is required", field)
		}
	}
	progress := ttcdwProgressValue(input, defaultProgress)
	finish := optBool(input.Options["finish"], optBool(input.Raw["isFinish"], false))
	if includeSubmitFields {
		finish = optBool(input.Options["finish"], optBool(input.Raw["isFinish"], progress >= ttcdwDuration(input) && ttcdwDuration(input) > 0))
	}
	clockInRule := optString(input.Options["clockInRule"], strOf(input.Raw["clockInRule"]))
	timeLimit := optString(input.Options["timeLimit"], strOf(input.Raw["timeLimit"]))
	if (clockInRule == "" || timeLimit == "") && !optBool(input.Options["skipParamFetch"], false) {
		cookieStr := sess.Cookies
		if cookieStr == "" {
			cookieStr = sess.Token
		}
		cache := &ttcdwApi.TtcdwUserCache{
			Account: sess.Account,
			Cookies: cookieStrToSlice(cookieStr),
		}
		param, err := ttcdwCourseParamProvider(cache, sourceId, courseId)
		if err != nil {
			return ttcdwProgressPrepared{}, err
		}
		if clockInRule == "" {
			clockInRule = param.ClockInRule
		}
		if timeLimit == "" {
			timeLimit = param.TimeLimit
		}
	}
	opts := ttcdwApi.StudyProgressSubmitOptions{
		ProgressURL:  optString(input.Options["progressUrl"], optString(input.Options["tickerUrl"], firstTtcdwString(strOf(input.Raw["progressUrl"]), strOf(input.Raw["tickerUrl"])))),
		OrgID:        orgId,
		CourseID:     courseId,
		ItemID:       itemId,
		VideoID:      videoId,
		PlayProgress: progress,
		SegID:        segmentId,
		IsFinish:     finish,
		Type:         optString(input.Options["type"], firstTtcdwString(strOf(input.Raw["type"]), "1")),
		Tjzj:         optString(input.Options["tjzj"], firstTtcdwString(strOf(input.Raw["tjzj"]), "1")),
		ClockInDot:   optString(input.Options["clockInDot"], firstTtcdwString(strOf(input.Raw["clockInDot"]), strconv.Itoa(progress))),
		SourceID:     sourceId,
		ChapterID:    optString(input.Options["chapterId"], strOf(input.Raw["chapterId"])),
		ClockInRule:  firstTtcdwString(clockInRule, "0"),
		TimeLimit:    firstTtcdwString(timeLimit, "-1"),
		EventType:    optString(input.Options["eventType"], firstTtcdwString(strOf(input.Raw["eventType"]), "study")),
		CourseType:   optString(input.Options["courseType"], firstTtcdwString(strOf(input.Raw["courseType"]), "2")),
		PlatformID:   optString(input.Options["platformId"], firstTtcdwString(strOf(input.Raw["platformId"]), "13145854983311")),
		Referer:      optString(input.Options["referer"], strOf(input.Raw["referer"])),
	}
	if opts.ProgressURL == "" {
		opts.ProgressURL = ttcdwApi.StudyProgressURL(orgId)
	}
	raw := map[string]interface{}{
		"submitMode":      "progress",
		"intervalSeconds": 30,
		"tickerUrl":       opts.ProgressURL,
		"progressUrl":     opts.ProgressURL,
		"courseParamUrl":  ttcdwApi.CourseParamURL(sourceId, courseId),
		"videoId":         videoId,
		"courseId":        courseId,
		"itemId":          itemId,
		"segmentId":       segmentId,
		"orgId":           orgId,
		"sourceId":        sourceId,
		"projectId":       sourceId,
		"playProgress":    progress,
		"isFinish":        finish,
		"type":            opts.Type,
		"tjzj":            opts.Tjzj,
		"clockInDot":      opts.ClockInDot,
		"clockInRule":     opts.ClockInRule,
		"timeLimit":       opts.TimeLimit,
		"eventType":       opts.EventType,
		"courseType":      opts.CourseType,
		"platformId":      opts.PlatformID,
	}
	if opts.ChapterID != "" {
		raw["chapterId"] = opts.ChapterID
	}
	return ttcdwProgressPrepared{Options: opts, Raw: raw}, nil
}

func ttcdwProgressValue(input TaskInput, def int) int {
	for _, v := range []interface{}{input.Options["playProgress"], input.Options["progress"], input.Options["currentTime"], input.Raw["playProgress"], input.Raw["progress"]} {
		if n, ok := optFloat(v); ok {
			return int(n)
		}
	}
	return def
}

func ttcdwProgressProvided(input TaskInput) bool {
	for _, v := range []interface{}{input.Options["playProgress"], input.Options["progress"], input.Options["currentTime"]} {
		if _, ok := optFloat(v); ok {
			return true
		}
	}
	return false
}

func ttcdwDuration(input TaskInput) int {
	for _, v := range []interface{}{input.Options["duration"], input.Raw["duration"]} {
		if n, ok := optFloat(v); ok {
			return int(n)
		}
	}
	return 0
}

type ttcdwTickerPrepared struct {
	TickerURL      string
	ServerDataName string
	TickerData     string
	Payload        ttcdwApi.TickerPayload
	PlayedRanges   string
}

func runTaskTtcdwDESPrepare(input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareTtcdwTicker(input)
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "ttcdw", TaskID: input.ID, Status: "prepared",
		Message: "DES ticker payload prepared; host should call action=tick every 30s",
		Raw: map[string]interface{}{
			"intervalSeconds": 30,
			"tickerUrl":       prepared.TickerURL,
			"serverDataName":  prepared.ServerDataName,
			"tickerData":      prepared.TickerData,
			"payload":         prepared.Payload,
			"playedRanges":    prepared.PlayedRanges,
		},
	}, nil
}

func runTaskTtcdwDESTick(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareTtcdwTicker(input)
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := map[string]interface{}{
		"intervalSeconds": 30,
		"tickerUrl":       prepared.TickerURL,
		"serverDataName":  prepared.ServerDataName,
		"tickerData":      prepared.TickerData,
		"payload":         prepared.Payload,
		"playedRanges":    prepared.PlayedRanges,
		"realSubmit":      false,
	}
	if !optBool(input.Options["realSubmit"], false) {
		return RunTaskResult{
			Platform: "ttcdw", TaskID: input.ID, Status: "dry_run",
			Message: "ttcdw DES ticker dry-run (set options.realSubmit=true and provide tickerUrl to submit)",
			Raw:     raw,
		}, nil
	}
	if prepared.TickerURL == "" {
		return RunTaskResult{}, fmt.Errorf("ttcdw: action=tick realSubmit requires raw.tickerUrl or options.tickerUrl")
	}
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	resp, err := ttcdwTickerSubmitProvider(cache, prepared.TickerURL, prepared.ServerDataName, prepared.TickerData)
	if err != nil {
		return RunTaskResult{}, err
	}
	ok, msg, err := ttcdwApi.ParseStudySubmitResult(resp)
	if err != nil {
		return RunTaskResult{}, err
	}
	if !ok {
		return RunTaskResult{}, fmt.Errorf("ttcdw: ticker submit rejected: %s", msg)
	}
	raw["realSubmit"] = true
	raw["message"] = msg
	return RunTaskResult{
		Platform: "ttcdw", TaskID: input.ID, Status: "submitted",
		Message: msg,
		Raw:     raw,
	}, nil
}

func prepareTtcdwTicker(input TaskInput) (ttcdwTickerPrepared, error) {
	companyCode := optString(input.Options["companyCode"], strOf(input.Raw["companyCode"]))
	userID := optString(input.Options["userId"], strOf(input.Raw["userId"]))
	resIDRaw := optValue(input.Options["resId"], input.Raw["resId"])
	if ttcdwValueEmpty(resIDRaw) {
		resIDRaw = optValue(input.Options["videoId"], input.Raw["videoId"])
	}
	if ttcdwValueEmpty(resIDRaw) {
		resIDRaw = input.ID
	}
	courseID := optString(input.Options["tickerCourseId"], ttcdwTickerCourseID(input))
	courseType := optString(input.Options["courseType"], ttcdwCourseType(input))
	playedRanges := ttcdwPlayedRanges(input)
	for field, val := range map[string]string{
		"companyCode":  companyCode,
		"userId":       userID,
		"courseId":     courseID,
		"playedRanges": playedRanges,
	} {
		if val == "" {
			return ttcdwTickerPrepared{}, fmt.Errorf("ttcdw: action=tick requires %s (from task raw or options)", field)
		}
	}
	tickerTime := optInt64(input.Options["tickerTime"], time.Now().UnixMilli())
	tickerData, payload, err := ttcdwApi.BuildTickerData(companyCode, userID, normalizeTtcdwResID(resIDRaw), courseID, courseType, tickerTime, playedRanges)
	if err != nil {
		return ttcdwTickerPrepared{}, err
	}
	return ttcdwTickerPrepared{
		TickerURL:      optString(input.Options["tickerUrl"], strOf(input.Raw["tickerUrl"])),
		ServerDataName: optString(input.Options["serverDataName"], optString(input.Options["tickerDataName"], "tickerData")),
		TickerData:     tickerData,
		Payload:        payload,
		PlayedRanges:   playedRanges,
	}, nil
}

func ttcdwTickerCourseID(input TaskInput) string {
	if v := strOf(input.Raw["tickerCourseId"]); v != "" {
		return v
	}
	if v := strOf(input.Raw["shortCourseId"]); v != "" {
		return v
	}
	return strOf(input.Raw["courseId"])
}

func ttcdwCourseType(input TaskInput) string {
	if v := strOf(input.Raw["courseType"]); v != "" {
		return v
	}
	return "share"
}

func firstTtcdwString(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func ttcdwPlayedRanges(input TaskInput) string {
	if v := strOf(input.Options["playedRanges"]); v != "" {
		return v
	}
	end, ok := optFloat(input.Options["playedEnd"])
	if !ok {
		end, ok = optFloat(input.Options["currentTime"])
	}
	if !ok {
		return ""
	}
	start := 0.0
	if v, ok := optFloat(input.Options["playedStart"]); ok {
		start = v
	}
	return fmt.Sprintf("%s-%s", formatTtcdwSecond(start), formatTtcdwSecond(end))
}

func optString(v interface{}, def string) string {
	if s := strOf(v); s != "" {
		return s
	}
	return def
}

func optValue(v interface{}, def interface{}) interface{} {
	if v != nil {
		return v
	}
	return def
}

func ttcdwValueEmpty(v interface{}) bool {
	if v == nil {
		return true
	}
	if s, ok := v.(string); ok {
		return strings.TrimSpace(s) == ""
	}
	return false
}

func optFloat(v interface{}) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case string:
		f, err := strconv.ParseFloat(n, 64)
		return f, err == nil
	default:
		return 0, false
	}
}

func optInt64(v interface{}, def int64) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int:
		return int64(n)
	case int64:
		return n
	case string:
		if i, err := strconv.ParseInt(n, 10, 64); err == nil {
			return i
		}
	}
	return def
}

func formatTtcdwSecond(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}

func normalizeTtcdwResID(v interface{}) interface{} {
	switch x := v.(type) {
	case float64:
		if x == float64(int64(x)) {
			return int64(x)
		}
		return x
	case int, int64:
		return x
	case string:
		s := strings.TrimSpace(x)
		if i, err := strconv.ParseInt(s, 10, 64); err == nil {
			return i
		}
		return s
	default:
		return v
	}
}

func ttcdwVideoType(coursewareType int) string {
	switch coursewareType {
	case 1:
		return "video"
	case 2:
		return "document"
	default:
		return "resource"
	}
}

// getCourseDetailTtcdw expands a project into its classroom list.
// input.ID = project.CourseProjectId; input.Raw["classId"] = project.ClassId
// Passes projectId and orgId into classroom Raw for downstream video expansion.
func getCourseDetailTtcdw(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	cookieStr := sess.Cookies
	if cookieStr == "" {
		cookieStr = sess.Token
	}
	cache := &ttcdwApi.TtcdwUserCache{
		Account: sess.Account,
		Cookies: cookieStrToSlice(cookieStr),
	}
	project := ttcdwAgg.TtcdwProject{
		CourseProjectId: input.ID,
		ClassId:         strOf(input.Raw["classId"]),
		OrgId:           strOf(input.Raw["orgId"]),
	}
	classrooms, err := ttcdwClassRoomProvider(cache, project)
	if err != nil {
		return CourseDetailResult{}, err
	}
	items := make([]CourseItem, 0, len(classrooms))
	for _, c := range classrooms {
		items = append(items, CourseItem{
			ID:   c.ItemId,
			Name: c.Title + " — " + c.Name,
			Raw: map[string]interface{}{
				"segmentId": c.SegmentId,
				"projectId": input.ID,                  // propagate for video expansion
				"orgId":     strOf(input.Raw["orgId"]), // propagate for video expansion
			},
		})
	}
	return CourseDetailResult{Platform: "ttcdw", ParentID: input.ID, Items: items}, nil
}
