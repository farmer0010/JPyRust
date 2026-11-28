import re

from jpyrust import task

POSITIVE = {"good", "great", "love", "excellent", "fast", "happy", "nice"}
NEGATIVE = {"bad", "slow", "hate", "terrible", "broken", "sad", "awful"}


def score(text):
    words = re.findall(r"[a-z']+", text.lower())
    total = sum(w in POSITIVE for w in words) - sum(w in NEGATIVE for w in words)
    label = "positive" if total > 0 else "negative" if total < 0 else "neutral"
    return {"label": label, "score": total, "words": len(words)}


@task("SENTIMENT")
def sentiment(data, args):
    return score(data.decode("utf-8"))


@task("SENTENCES")
def sentences(data, args):
    for sentence in re.split(r"(?<=[.!?])\s+", data.decode("utf-8").strip()):
        if sentence:
            yield {"sentence": sentence, **score(sentence)}
