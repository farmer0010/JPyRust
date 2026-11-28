from jpyrust import task


@task("MATH_ADD")
def add(data, args):
    a, b = (float(v) for v in args.split()[:2])
    return {"result": a + b}
