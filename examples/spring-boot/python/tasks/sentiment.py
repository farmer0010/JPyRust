import re

from jpyrust import task

POSITIVE = {"good", "great", "love", "excellent", "fast", "happy", "nice"}
NEGATIVE = {"bad", "slow", "hate", "terrible", "broken", "sad", "awful"}


@task("SENTIMENT")
def sentiment(data, args):
    words = re.findall(r"[a-z']+", data.decode("utf-8").lower())
    score = sum(w in POSITIVE for w in words) - sum(w in NEGATIVE for w in words)
    label = "positive" if score > 0 else "negative" if score < 0 else "neutral"
    return {"label": label, "score": score, "words": len(words)}
