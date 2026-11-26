import json

import pandas as pd
from sklearn.linear_model import LinearRegression
from textblob import TextBlob

from jpyrust import task


@task("NLP_TEXTBLOB")
def sentiment(data, args):
    pol = TextBlob(data.decode("utf-8")).sentiment.polarity
    sent = "POSITIVE" if pol > 0.1 else ("NEGATIVE" if pol < -0.1 else "NEUTRAL")
    return f"{sent} (Polarity: {pol:.2f})"


@task("REGRESSION")
def regression(data, args):
    df = pd.DataFrame(json.loads(data.decode("utf-8")), columns=["x", "y"])
    model = LinearRegression().fit(df[["x"]], df["y"])
    return f"Slope: {model.coef_[0]:.4f}, Intercept: {model.intercept_:.4f}"
