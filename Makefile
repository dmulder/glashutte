# Containerised build for Glashutte, in the style of himmelblau's Makefile: pick podman (or
# docker), build an image with every build dependency, then run the build inside it.
#
#   make help            show targets
#   make build/apk       debug + release APKs  ->  ./dist/
#
# The proprietary VITURE SDK is never baked into the image and never committed. If you have
# it locally (Android/libs/viture-sdk, or VITURE_SDK_DIR=/path), it is mounted read-only into
# the build container and the resulting APKs embed it. Without it the stub backend is built.

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
# VITURE=off forces the stub backend even when an SDK is present.
VITURE         ?= auto
# Extra Gradle arguments (e.g. GRADLE_ARGS=--info) and extra container-run arguments.
GRADLE_ARGS    ?=
RUN_ARGS       ?=

OUT_ABS  := $(abspath $(OUTPUT_DIR))
SDK_ABS  := $(abspath $(VITURE_SDK_DIR))
HAVE_SDK := $(if $(wildcard $(SDK_ABS)/include/viture_glasses_provider.h),yes,no)

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
	$(RUN_ARGS) $(IMAGE)

.DEFAULT_GOAL := help
.PHONY: help info check-engine image build/apk build/apk-debug build/apk-release test shell gradle clean distclean

help: ## Show this help
	@printf "Glashutte container build\n\nUsage:\n  make <target>\n\nTargets:\n"
	@awk 'BEGIN {FS = ":.*##"} /^[A-Za-z0-9_\/.-]+:.*##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf "\nVariables (override as VAR=value):\n"
	@printf "  CONTAINER_ENGINE  podman, else docker (detected: %s)\n" "$(if $(CONTAINER_ENGINE),$(CONTAINER_ENGINE),none found)"
	@printf "  OUTPUT_DIR        where APKs are written (default: dist)\n"
	@printf "  VITURE_SDK_DIR    local VITURE SDK, mounted read-only (default: Android/libs/viture-sdk; found: %s)\n" "$(HAVE_SDK)"
	@printf "  VITURE=off        force the stub backend even if an SDK is present\n"
	@printf "  BASE_IMAGE        base image (default: docker.io/library/ubuntu:24.04)\n"
	@printf "  GRADLE_ARGS, RUN_ARGS, IMAGE, PLATFORM, CACHE_VOLUME, WORK_VOLUME, MOUNT_LABEL\n"
	@printf "\nThe VITURE SDK is never baked into the image or committed.\n"

check-engine:
	@command -v "$(CONTAINER_ENGINE)" > /dev/null 2>&1 || { echo "error: container engine '$(CONTAINER_ENGINE)' not found; install podman or docker, or set CONTAINER_ENGINE=" >&2; exit 1; }

info: check-engine ## Show detected engine, mounts and SDK status
	@echo "engine:      $(CONTAINER_ENGINE) ($(ENGINE_VERSION))"
	@echo "image:       $(IMAGE) (platform $(PLATFORM))"
	@echo "user flags:  $(USER_FLAGS)"
	@echo "VITURE SDK:  $(HAVE_SDK) ($(SDK_ABS)); VITURE=$(VITURE)"
	@echo "output:      $(OUT_ABS)"
	@echo "volumes:     $(CACHE_VOLUME) (caches), $(WORK_VOLUME) (scratch build tree)"

image: check-engine ## Build the build-environment image (no SDK, no source inside)
	$(CONTAINER_ENGINE) build --platform $(PLATFORM) --build-arg BASE_IMAGE=$(BASE_IMAGE) \
		-t $(IMAGE) -f Dockerfile .

$(OUT_ABS):
	mkdir -p $@

build/apk: image $(OUT_ABS) ## Build debug and release APKs into dist/
	$(RUN) apk-all

build/apk-debug: image $(OUT_ABS) ## Build only the debug APK
	$(RUN) apk-debug

build/apk-release: image $(OUT_ABS) ## Build only the (unsigned) release APK
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
