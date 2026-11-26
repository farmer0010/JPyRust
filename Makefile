UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)

ifeq ($(UNAME_S),Darwin)
  OS := darwin
  LIB := libjpyrust.dylib
  EXT := dylib
else ifeq ($(UNAME_S),Linux)
  OS := linux
  LIB := libjpyrust.so
  EXT := so
else
  OS := windows
  LIB := jpyrust.dll
  EXT := dll
endif

ifneq ($(filter $(UNAME_M),arm64 aarch64),)
  ARCH := aarch64
else
  ARCH := x86_64
endif

PLATFORM := $(OS)-$(ARCH)
NATIVE_DIR := java-api/src/main/resources/natives/$(PLATFORM)
PYTHON ?= .venv/bin/python

.PHONY: check native rust-check java-test python-test jar provision-test

check: rust-check java-test python-test

native:
	cd rust-bridge && cargo build --release
	mkdir -p $(NATIVE_DIR)
	cp rust-bridge/target/release/$(LIB) $(NATIVE_DIR)/jpyrust.$(EXT)

rust-check:
	cd rust-bridge && cargo clippy --all-targets -- -D warnings
	cd rust-bridge && cargo test

java-test:
	./gradlew :java-api:test :demo-web:compileJava

$(PYTHON):
	uv venv --python 3.11 .venv
	uv pip install --python .venv numpy opencv-python-headless pytest

python-test: $(PYTHON)
	$(PYTHON) -m pytest python-core/tests

jar:
	./gradlew :java-api:jar

provision-test:
	JPYRUST_PROVISION_TEST=true ./gradlew :java-api:test --tests com.jpyrust.PythonProvisioningTest
