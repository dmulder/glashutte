# Containerised build for Glashutte, in the style of himmelblau's Makefile: pick podman (or
# docker), build an image with every build dependency, then run the build inside it.
#
#   make help            show targets
#   make build/apk       debug + release APKs  ->  ./dist/
#
# The proprietary VITURE SDK is never baked into the image and never committed. Put it in
# Android/libs/viture-sdk (or pass VITURE_SDK_DIR=/path); it is mounted read-only into the
# build container and the resulting APKs embed it (file name ends in -with-viture-sdk.apk).
#
# A build WITHOUT the SDK is a stub: it installs and shows the workspace but can NEVER do
# head tracking. So `make build/apk` FAILS when the SDK is missing. Build the stub on purpose
# with `make build/apk VITURE=off` (file name ends in -no-viture-sdk.apk).

# Container engine: podman first, then docker; override with CONTAINER_ENGINE=...
CONTAINER_ENGINE ?= $(shell command -v podman 2>/dev/null || command -v docker 2>/dev/null)

IMAGE          ?= localhost/glashutte-build:latest
BASE_IMAGE     ?= docker.io/library/ubuntu:24.04
# The Android NDK/SDK tools in the image are x86_64-only; emulated on arm64 hosts.
PLATFORM       ?= linux/amd64
CACHE_VOLUME   ?= glashutte-cache
WORK_VOLUME    ?= glashutte-work
OUTPUT_DIR     ?= dist
# Local VITURE SDK (host path). Mounted only if it contains the headers.
VITURE_SDK_DIR ?= $(CURDIR)/Android/libs/viture-sdk
# VITURE=required (default): fail unless the SDK is present. VITURE=off: build the head-tracking-less
# stub on purpose, even if an SDK is present. (VITURE=auto is an alias of required.)
VITURE         ?= required
# Extra Gradle arguments (e.g. GRADLE_ARGS=--info) and extra container-run arguments.
GRADLE_ARGS    ?=
RUN_ARGS       ?=
# Optional Maven Central mirror or organization repository cache.
MAVEN_CENTRAL_URL ?=

OUT_ABS  := $(abspath $(OUTPUT_DIR))
SDK_ABS  := $(abspath $(VITURE_SDK_DIR))
SDK_HEADER := $(wildcard $(SDK_ABS)/include/viture_glasses_provider.h)
SDK_LIB    := $(wildcard $(SDK_ABS)/libs/arm64-v8a/libglasses.so $(SDK_ABS)/android/arm64-v8a/libglasses.so)
HAVE_SDK   := $(if $(and $(SDK_HEADER),$(SDK_LIB)),yes,no)

ENGINE_VERSION := $(shell $(CONTAINER_ENGINE) --version 2>/dev/null)
IS_PODMAN      := $(findstring podman,$(ENGINE_VERSION))
HOST_UID       := $(shell id -u)
HOST_GID       := $(shell id -g)

# SELinux relabelling (:Z private for the tree we mount, :z shared for the SDK). Harmless where
# SELinux is not enabled. Override MOUNT_LABEL= (empty) to disable.
MOUNT_LABEL ?= Z

# User mapping so files written to ./dist are owned by you:
#  - root: nothing to do
#  - rootless podman: keep your uid inside the container
#  - rootless docker: container root already is you
#  - rootful docker: run as your uid/gid
ifeq ($(HOST_UID),0)
  USER_FLAGS :=
else ifneq ($(IS_PODMAN),)
  USER_FLAGS := --userns=keep-id
else ifneq ($(findstring rootless,$(shell $(CONTAINER_ENGINE) info --format '{{.SecurityOptions}}' 2>/dev/null)),)
  USER_FLAGS :=
else
  USER_FLAGS := --user $(HOST_UID):$(HOST_GID)
endif

TTY_FLAGS := -i $(shell [ -t 0 ] && echo -t)

comma := ,
SRC_LABEL := $(if $(MOUNT_LABEL),$(comma)$(MOUNT_LABEL))
SDK_LABEL := $(if $(MOUNT_LABEL),$(comma)z)
SDK_MOUNT := $(if $(filter yes,$(HAVE_SDK)),-v $(SDK_ABS):/viture-sdk:ro$(SDK_LABEL))

RUN = $(CONTAINER_ENGINE) run --rm $(TTY_FLAGS) --platform $(PLATFORM) $(USER_FLAGS) \
	-v $(CURDIR):/src:ro$(SRC_LABEL) \
	-v $(OUT_ABS):/out$(if $(MOUNT_LABEL),:$(MOUNT_LABEL)) \
	-v $(CACHE_VOLUME):/cache \
	-v $(WORK_VOLUME):/work \
	$(SDK_MOUNT) \
	-e GLASHUTTE_VITURE_SDK=$(VITURE) -e GRADLE_ARGS="$(GRADLE_ARGS)" \
	-e MAVEN_CENTRAL_URL="$(MAVEN_CENTRAL_URL)" \
	$(RUN_ARGS) $(IMAGE)

.DEFAULT_GOAL := help
.PHONY: help info check-engine check-sdk image build/apk build/apk-debug build/apk-release test shell gradle clean distclean

help: ## Show this help
	@printf "Glashutte container build\n\nUsage:\n  make <target>\n\nTargets:\n"
	@awk 'BEGIN {FS = ":.*##"} /^[A-Za-z0-9_\/.-]+:.*##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf "\nVariables (override as VAR=value):\n"
	@printf "  CONTAINER_ENGINE  podman, else docker (detected: %s)\n" "$(if $(CONTAINER_ENGINE),$(CONTAINER_ENGINE),none found)"
	@printf "  OUTPUT_DIR        where APKs are written (default: dist)\n"
	@printf "  VITURE_SDK_DIR    local VITURE SDK, mounted read-only (default: Android/libs/viture-sdk; found: %s)\n" "$(HAVE_SDK)"
	@printf "  VITURE=off        build the head-tracking-less STUB on purpose (default: fail if the SDK is missing)\n"
	@printf "  BASE_IMAGE        base image (default: docker.io/library/ubuntu:24.04)\n"
	@printf "  GRADLE_ARGS, RUN_ARGS, IMAGE, PLATFORM, CACHE_VOLUME, WORK_VOLUME, MOUNT_LABEL\n"
	@printf "  MAVEN_CENTRAL_URL optional Maven Central mirror or repository cache URL\n"
	@printf "\nThe VITURE SDK is never baked into the image or committed.\n"

check-engine:
	@command -v "$(CONTAINER_ENGINE)" > /dev/null 2>&1 || { echo "error: container engine '$(CONTAINER_ENGINE)' not found; install podman or docker, or set CONTAINER_ENGINE=" >&2; exit 1; }

check-sdk:
ifneq ($(VITURE),off)
	@if [ "$(HAVE_SDK)" != "yes" ]; then \
	  echo "" >&2; \
	  echo "error: VITURE SDK not found in $(SDK_ABS)" >&2; \
	  echo "  need include/viture_glasses_provider.h and libs/arm64-v8a/libglasses.so (or android/arm64-v8a/libglasses.so)." >&2; \
	  echo "  A build without the SDK is a STUB with NO head tracking (3DoF/6DoF)." >&2; \
	  echo "  - have the SDK:   put it in Android/libs/viture-sdk or run: make build/apk VITURE_SDK_DIR=/path/to/sdk" >&2; \
	  echo "  - want the stub:  make build/apk VITURE=off" >&2; \
	  echo "" >&2; \
	  exit 1; \
	fi
endif

info: check-engine ## Show detected engine, mounts and SDK status
	@echo "engine:      $(CONTAINER_ENGINE) ($(ENGINE_VERSION))"
	@echo "image:       $(IMAGE) (platform $(PLATFORM))"
	@echo "user flags:  $(USER_FLAGS)"
	@echo "VITURE SDK:  found=$(HAVE_SDK) ($(SDK_ABS)); VITURE=$(VITURE) -> $(if $(filter off,$(VITURE)),STUB build (no head tracking),$(if $(filter yes,$(HAVE_SDK)),with-viture-sdk build,build will FAIL: SDK missing))"
	@echo "output:      $(OUT_ABS)"
	@echo "volumes:     $(CACHE_VOLUME) (caches), $(WORK_VOLUME) (scratch build tree)"

image: check-engine ## Build the build-environment image (no SDK, no source inside)
	$(CONTAINER_ENGINE) build --platform $(PLATFORM) --build-arg BASE_IMAGE=$(BASE_IMAGE) \
		-t $(IMAGE) -f Dockerfile .

$(OUT_ABS):
	mkdir -p $@

build/apk: check-sdk image $(OUT_ABS) ## Build debug and release APKs into dist/
	$(RUN) apk-all

build/apk-debug: check-sdk image $(OUT_ABS) ## Build only the debug APK
	$(RUN) apk-debug

build/apk-release: check-sdk image $(OUT_ABS) ## Build only the (unsigned) release APK
	$(RUN) apk-release

test: image $(OUT_ABS) ## Run unit tests in the container
	$(RUN) test

gradle: image $(OUT_ABS) ## Run Gradle in the container: make gradle GRADLE_TASKS=":app:dependencies"
	$(RUN) gradle $(GRADLE_TASKS)

shell: image $(OUT_ABS) ## Interactive shell in the build container
	$(RUN) shell

clean: check-engine ## Remove dist/ and the scratch build volume (keeps dependency caches)
	rm -rf $(OUT_ABS)
	-$(CONTAINER_ENGINE) volume rm $(WORK_VOLUME)

distclean: clean ## clean + remove the dependency cache volume and the image
	-$(CONTAINER_ENGINE) volume rm $(CACHE_VOLUME)
	-$(CONTAINER_ENGINE) rmi $(IMAGE)
