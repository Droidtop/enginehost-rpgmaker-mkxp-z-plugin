L_PATH := $(call my-dir)

# Include dependecies
include $(L_PATH)/SDL2/Android.mk 
include $(L_PATH)/SDL2_image/Android.mk
include $(L_PATH)/SDL2_ttf/Android.mk
include $(L_PATH)/SDL2_sound.mk
include $(L_PATH)/libogg.mk
include $(L_PATH)/libvorbis.mk
include $(L_PATH)/libtheora.mk
include $(L_PATH)/openal.mk
include $(L_PATH)/pixman.mk
include $(L_PATH)/physfs.mk
include $(L_PATH)/uchardet.mk
include $(L_PATH)/libiconv.mk
include $(L_PATH)/openssl.mk
include $(L_PATH)/ruby.mk

# Include one namespaced mkxp binary per bundled Ruby ABI. Only the selected
# binary is loaded for a launch, so the two VMs never share a process.
ENGINEHOST_MKXP_MODULE := mkxp-z-ruby31
ENGINEHOST_RUBY_MODULE := ruby31
include $(L_PATH)/mkxp-z.mk

ENGINEHOST_MKXP_MODULE := mkxp-z-ruby19
ENGINEHOST_RUBY_MODULE := ruby19
include $(L_PATH)/mkxp-z.mk
