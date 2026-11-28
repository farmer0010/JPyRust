from jpyrust import task


@task("ECHO")
def echo(data, args):
    return data


@task("SPIN")
def spin(data, args):
    total = 0
    for i in range(int(args)):
        total += i * i
    return str(total)


@task("SCALE")
def scale(data, args):
    return data * 2
