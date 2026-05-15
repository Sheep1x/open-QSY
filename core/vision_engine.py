import base64
import json
import logging

import cv2
import numpy as np
from openai import OpenAI

logger = logging.getLogger(__name__)

VISION_PROMPT = """你是一个屏幕内容识别助手。请识别图片中的考试/测验题目，严格按以下JSON格式返回：

{
    "question": "题目的完整文本",
    "options": {
        "A": "选项A内容",
        "B": "选项B内容",
        "C": "选项C内容",
        "D": "选项D内容"
    },
    "type": "单选/多选/判断/填空",
    "selected": []
}

注意：
1. 只识别题目区域，忽略页面其他无关文字
2. 如果图片中没有题目，返回 {"empty": true}
3. 选项数量不固定，根据实际情况增减
4. 如果用户已选择某个选项，记录在selected中
5. 严格返回JSON，不要附加任何其他文字"""


def _frame_to_base64(frame: np.ndarray, quality: int = 85) -> str:
    _, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
    return base64.b64encode(buf).decode("utf-8")


def _parse_response(text: str) -> dict | None:
    text = text.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = [l for l in lines if not l.strip().startswith("```")]
        text = "\n".join(lines)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}") + 1
        if start != -1 and end > start:
            try:
                return json.loads(text[start:end])
            except json.JSONDecodeError:
                pass
        logger.warning("Failed to parse vision response: %s", text[:200])
        return None


class VisionEngine:
    def __init__(self, base_url: str, api_key: str, model: str):
        self.client = OpenAI(base_url=base_url, api_key=api_key)
        self.model = model

    def recognize(self, frame: np.ndarray) -> dict | None:
        b64 = _frame_to_base64(frame)
        resp = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": VISION_PROMPT},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{b64}",
                                "detail": "high",
                            },
                        },
                    ],
                }
            ],
            max_tokens=1024,
            temperature=0.1,
        )
        text = resp.choices[0].message.content
        result = _parse_response(text)
        if result is None:
            logger.warning("Failed to parse vision response, returning empty")
            return {"empty": True}
        return result

    def answer(self, question: str, options: dict | None = None) -> str | None:
        prompt = f"请直接给出以下题目的答案，简洁回答即可。\n\n题目：{question}"
        if options:
            opts = "\n".join(f"{k}. {v}" for k, v in options.items())
            prompt += f"\n\n选项：\n{opts}"
        resp = self.client.chat.completions.create(
            model=self.model,
            messages=[{"role": "user", "content": prompt}],
            max_tokens=512,
            temperature=0.1,
        )
        return resp.choices[0].message.content.strip()
