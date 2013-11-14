ifeq ($(BOARD_USES_QCNE),true)
ifneq ($(BUILD_TINY_ANDROID),true)
ifneq (,$(filter $(QCOM_BOARD_PLATFORMS),$(TARGET_BOARD_PLATFORM)))

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := services-ext

LOCAL_JAVA_LIBRARIES := telephony-common services

include $(BUILD_JAVA_LIBRARY)

endif
endif
endif
