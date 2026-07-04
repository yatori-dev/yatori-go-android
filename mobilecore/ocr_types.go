package mobilecore

type OcrChallenge struct {
	TaskID      string `json:"taskId"`
	Platform    string `json:"platform"`
	Type        string `json:"type"`                  // "image_ocr" | "slider" | "face"
	ImageBase64 string `json:"imageBase64"`
	OutputCols  int    `json:"outputCols,omitempty"`  // >0 → Android calls recognizeSemi(bitmap, outputCols)
	Hint        string `json:"hint,omitempty"`
}

type OcrResult struct {
	TaskID string `json:"taskId"`
	Type   string `json:"type"`  // must match the challenge type
	Text   string `json:"text"`  // recognized characters (image_ocr)
}
