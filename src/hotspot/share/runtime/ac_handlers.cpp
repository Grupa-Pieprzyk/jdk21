#include "precompiled.hpp"
#include "runtime/ac_handlers.hpp"
#include "runtime/jniHandles.inline.hpp"

const uint8_t handlers[HANDLERS_COUNT * sizeof( uintptr_t )] = {};

jobject JVMTIjni_reference(Handle hndl) {
  return JNIHandles::make_local(hndl());
}

jvmtiErrorAC JVMTIGetClassLoader(jclass klass, jobject* classloader_ptr)
{
    {
    const oop k_mirror = JNIHandles::resolve_external_guard(klass);
    JavaThread* current_thread = JavaThread::current();
    HandleMark hm(current_thread);
    Klass* k = java_lang_Class::as_Klass(k_mirror);

    oop result_oop = k->class_loader();
    if (result_oop == NULL) {
      *classloader_ptr = (jclass) JVMTIjni_reference(Handle());
      return JVMTIAC_ERROR_NONE;
    }
    Handle result_handle = Handle(current_thread, result_oop);
    jclass result_jnihandle = (jclass) JVMTIjni_reference(result_handle);
    *classloader_ptr = result_jnihandle;
  }
  return JVMTIAC_ERROR_NONE;
}