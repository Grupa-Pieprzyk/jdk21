#ifndef JVM_BP_AC_HANDLERS_HPP
#define JVM_BP_AC_HANDLERS_HPP

#include <cinttypes>
#include <type_traits>
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"

#define HANDLERS_COUNT 15

enum class handler_func_index
{
	ThreadAdd,
	ThreadRemove,
  JavaProtCall,
  JavaFindClassCall,
  JavaGetStaticFieldCall,
  JavaReserveMemory,
  JavaAllocateMemory,
  JavaDeallocateMemory
};

using fn_ThreadAdd = void(__stdcall*)(std::uintptr_t);
using fn_ThreadRemove = void(__stdcall*)(std::uintptr_t);
using fn_JavaProtCall = void(__stdcall*)(std::uint64_t, std::uintptr_t);
using fn_JavaFindClassCall = bool(__stdcall*)(const char*);
using fn_JavaGetStaticFieldCall = bool(__stdcall*)(const char*);

using fn_JavaReserveMemory = std::int8_t(__stdcall*)(void**, std::size_t, std::uint32_t);
using fn_JavaAllocateMemory = std::int8_t(__stdcall*)(void*, std::size_t, std::uint32_t);
using fn_JavaDeallocateMemory = std::int8_t(__stdcall*)(void*, std::size_t, bool);

//From jni_md.h
//
#ifndef JNIEXPORT
  #if (defined(__GNUC__) && ((__GNUC__ > 4) || (__GNUC__ == 4) && (__GNUC_MINOR__ > 2))) || __has_attribute(visibility)
    #ifdef ARM
      #define JNIEXPORT     __attribute__((externally_visible,visibility("default")))
    #else
      #define JNIEXPORT     __attribute__((visibility("default")))
    #endif
  #else
    #define JNIEXPORT
  #endif
#endif

#ifdef _WIN32
#define ADDRESS_OF_RETURN_ADDRESS _AddressOfReturnAddress( )
#else
#define ADDRESS_OF_RETURN_ADDRESS __builtin_return_address( 0 )
#endif

namespace blazingpack
{
   constexpr std::uint64_t hash_64_fnv1a( const char* str )
{
	std::uint64_t hash = 0xCBF29CE484222325;

	for ( auto i = 0u; str[ i ]; ++i )
	{
		hash ^= str[ i ];
		hash *= 0x100000001B3;
	}

	return hash;
}
};

#define FNV_64(str) (std::integral_constant<std::uint64_t, blazingpack::hash_64_fnv1a(str)>::value)

#define JAVA_CALL_AC_HANDLER(name, ...) const auto addr_##name = *(uintptr_t*)(handlers + (static_cast<uintptr_t>(handler_func_index::name) * sizeof(uintptr_t))); \
  if(addr_##name) \
    reinterpret_cast<fn_##name>(addr_##name)(__VA_ARGS__);

#define JAVA_CALL_AC_HANDLER_WITH_RESULT(name, result, ...) const auto addr_##name = *(uintptr_t*)(handlers + (static_cast<uintptr_t>(handler_func_index::name) * sizeof(uintptr_t))); \
  if(addr_##name) \
    result = reinterpret_cast<fn_##name>(addr_##name)(__VA_ARGS__);

#define JAVA_PROT_CALL_NOTIFY(name) JAVA_CALL_AC_HANDLER(JavaProtCall, FNV_64(name), std::uintptr_t( ADDRESS_OF_RETURN_ADDRESS ))

extern "C" JNIEXPORT const uint8_t handlers[];

typedef enum {
    JVMTIAC_ERROR_NONE = 0,
} jvmtiErrorAC;

extern "C" JNIEXPORT jvmtiErrorAC JVMTIGetClassLoader(jclass klass, jobject* classloader_ptr);

#endif // JVM_BP_AC_HANDLERS_HPP