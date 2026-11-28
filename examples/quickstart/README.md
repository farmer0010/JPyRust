# Quickstart

Calls Python functions from a plain Java program: numpy arrays sent as `NDArray` and returned typed, CPU-bound calls spread over four worker processes, and a Python exception surfacing as `TaskFailedException`.

```bash
make native                               # from the repository root, once
./gradlew :examples:quickstart:run
```

The first run downloads Python and installs numpy into `~/.jpyrust/quickstart`; later runs start in about a second.

- `python/tasks/stats.py` registers four tasks with `@task`; `STATS` and `SMOOTH` receive a `numpy.ndarray`.
- `python/requirements.txt` is the worker environment.
- `src/main/java/example/Quickstart.java` configures the bridge and calls the tasks.
