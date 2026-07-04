package mobilecore

import (
	"encoding/json"
	"testing"
)

func TestOcrChallengeRoundtrip(t *testing.T) {
	ch := OcrChallenge{
		TaskID:      "task-1",
		Platform:    "xuexitong",
		Type:        "image_ocr",
		ImageBase64: "iVBORw0KGgo=",
		OutputCols:  23,
		Hint:        "请输入4位验证码",
	}
	b, err := json.Marshal(ch)
	if err != nil {
		t.Fatal(err)
	}
	var out OcrChallenge
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.TaskID != ch.TaskID || out.Platform != ch.Platform || out.OutputCols != ch.OutputCols {
		t.Fatalf("roundtrip mismatch: %+v", out)
	}
}

func TestOcrResultRoundtrip(t *testing.T) {
	res := OcrResult{TaskID: "task-1", Type: "image_ocr", Text: "8k4p"}
	b, _ := json.Marshal(res)
	var out OcrResult
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.Text != "8k4p" {
		t.Fatalf("text mismatch: %q", out.Text)
	}
}

func TestOcrChallengeOmitsOutputCols(t *testing.T) {
	// OutputCols=0 should be omitted from JSON
	ch := OcrChallenge{TaskID: "t", Platform: "icve", Type: "image_ocr", ImageBase64: "abc"}
	b, _ := json.Marshal(ch)
	var m map[string]interface{}
	json.Unmarshal(b, &m)
	if _, ok := m["outputCols"]; ok {
		t.Fatal("outputCols=0 should be omitted from JSON")
	}
}
