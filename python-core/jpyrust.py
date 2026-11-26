_tasks = {}


def task(name=None):
    if callable(name):
        return task()(name)

    def register(fn):
        _tasks[(name or fn.__name__).upper()] = fn
        return fn

    return register


def registered_tasks():
    return dict(_tasks)
