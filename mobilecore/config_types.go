package mobilecore

// MobileConfig mirrors github.com/yatori-dev/yatori-go-mobile-core/config.Config
// using only standard JSON types so the mobilecore package stays CGO-free.
type MobileConfig struct {
	Setting Setting `json:"setting"`
	Users   []User  `json:"users"`
}

type Setting struct {
	BasicSetting  BasicSetting  `json:"basicSetting"`
	EmailInform   EmailInform   `json:"emailInform"`
	AiSetting     AiSetting     `json:"aiSetting"`
	ApiQueSetting ApiQueSetting `json:"apiQueSetting"`
}

type BasicSetting struct {
	CompletionTone int    `json:"completionTone,omitempty"` // 0=关 1=开，默认1
	ColorLog       int    `json:"colorLog,omitempty"`       // 0=关 1=开彩色日志
	LogOutFileSw   int    `json:"logOutFileSw,omitempty"`   // 0=不输出 1=输出日志文件
	LogLevel       string `json:"logLevel,omitempty"`       // debug/info/warn/error
	LogModel       int    `json:"logModel"`                 // 0=以视频为基准 1=以课程为基准
}

type EmailInform struct {
	Sw       int    `json:"sw"`
	SMTPHost string `json:"smtpHost"`
	SMTPPort string `json:"smtpPort"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

// AiSetting: AiType uses string to avoid importing ctype.
type AiSetting struct {
	AiType string `json:"aiType"` // CHATGLM/XINGHUO/TONGYI/DOUBAO/OPENAI/DEEPSEEK/METAAI/SILICON/OTHER
	AiUrl  string `json:"aiUrl"`
	Model  string `json:"model"`
	APIKEY string `json:"API_KEY"`
}

type ApiQueSetting struct {
	Url     string `json:"url"`
	ExType  string `json:"exType"`
	ExToken string `json:"exToken"`
}

type User struct {
	AccountType   string        `json:"accountType"` // xuexitong/icve/weiban/...
	URL           string        `json:"url"`
	RemarkName    string        `json:"remarkName,omitempty"`
	Account       string        `json:"account"`
	Password      string        `json:"password"`
	InformEmails  []string      `json:"informEmails"`
	CoursesCustom CoursesCustom `json:"coursesCustom"`
}

type CoursesCustom struct {
	StudyTime       string           `json:"studyTime"`
	CxNode          *int             `json:"cxNode,omitempty"`
	CxChapterTestSw *int             `json:"cxChapterTestSw,omitempty"`
	CxWorkSw        *int             `json:"cxWorkSw,omitempty"`
	CxExamSw        *int             `json:"cxExamSw,omitempty"`
	ShuffleSw       int              `json:"shuffleSw"`
	VideoModel      int              `json:"videoModel"`
	AutoExam        int              `json:"autoExam"`
	ExamAutoSubmit  int              `json:"examAutoSubmit"`
	ExcludeCourses  []string         `json:"excludeCourses"`
	IncludeCourses  []string         `json:"includeCourses"`
	CoursesSettings []CourseSetting  `json:"coursesSettings"`
}

type CourseSetting struct {
	Name         string   `json:"name"`
	IncludeExams []string `json:"includeExams"`
	ExcludeExams []string `json:"excludeExams"`
}
