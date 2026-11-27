# Quickstart

Calls Python functions from a plain Java program: a numpy computation on a binary payload, CPU-bound calls spread over four worker processes, and a Python exception surfacing as `TaskFailedException`.

```bash
make native                               # from the repository root, once
./gradlew :examples:quickstart:run
```

The first run downloads Python and installs numpy into `~/.jpyrust/quickstart`; later runs start in about a second.

- `python/tasks/stats.py` registers three tasks with `@task`.
- `python/requirements.txt` is the worker environment.
- `src/main/java/example/Quickstart.java` configures the bridge and calls the tasks.
