import json

import numpy as np

from jpyrust import task


@task("STATS")
def stats(values, args):
    return {"count": int(values.size), "mean": float(values.mean()), "p95": float(np.percentile(values, 95))}


@task("SMOOTH")
def smooth(values, args):
    window = int(args)
    return np.convolve(values, np.ones(window) / window, mode="valid")


@task("PRIMES")
def primes(data, args):
    limit = int(args)
    sieve = bytearray([1]) * (limit + 1)
    sieve[0:2] = b"\x00\x00"
    for i in range(2, int(limit ** 0.5) + 1):
        if sieve[i]:
            sieve[i * i::i] = bytes(len(sieve[i * i::i]))
    return str(sum(sieve))


@task("FAIL")
def fail(data, args):
    raise ValueError(json.dumps({"reason": args}))
