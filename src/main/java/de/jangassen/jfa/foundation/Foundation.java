// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// This file has been modified
package de.jangassen.jfa.foundation;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * @author spleaner
 * see <a href="http://developer.apple.com/documentation/Cocoa/Reference/ObjCRuntimeRef/Reference/reference.html">Documentation</a>
 */
@SuppressWarnings("unused")
public final class Foundation {
  private static final FoundationLibrary myFoundationLibrary;
  private static final Function myObjcMsgSend;
  private static final Map<String, RunnableInfo> ourMainThreadRunnables = new HashMap<>();
  private static final Object RUNNABLE_LOCK = new Object();
  private static Callback ourRunnableCallback;
  private static long ourCurrentRunnableCount = 0;

  static {
    FoundationLibrary foundationLibrary = null;
    Function objcMsgSend = null;
    try {
      foundationLibrary = Native.load("Foundation", FoundationLibrary.class, Collections.singletonMap("jna.encoding", "UTF8"));
      NativeLibrary nativeLibrary = ((Library.Handler) Proxy.getInvocationHandler(foundationLibrary)).getNativeLibrary();
      objcMsgSend = nativeLibrary.getFunction("objc_msgSend");
    } catch (RuntimeException e) {
      // Foundation not available
    }

    myFoundationLibrary = foundationLibrary;
    myObjcMsgSend = objcMsgSend;
  }

  private Foundation() {
  }

  public static boolean isAvailable() {
    return myFoundationLibrary != null && myObjcMsgSend != null;
  }

  public static void init() { /* fake method to init de.jangassen.foundation */ }

  /**
   * Get the ID of the NSClass with className
   */
  public static ID getObjcClass(String className) {
    return myFoundationLibrary.objc_getClass(className);
  }

  public static ID getProtocol(String name) {
    return myFoundationLibrary.objc_getProtocol(name);
  }

  public static Pointer createSelector(String s) {
    return myFoundationLibrary.sel_registerName(s);
  }

  private static Object[] prepInvoke(ID id, Pointer selector, Object[] args) {
    Object[] invokArgs = new Object[args.length + 2];
    invokArgs[0] = id;
    invokArgs[1] = selector;
    System.arraycopy(args, 0, invokArgs, 2, args.length);
    return invokArgs;
  }

  public static ID invoke(final ID id, final Pointer selector, Object... args) {
    // objc_msgSend is called with the calling convention of the target method
    // on x86_64 this does not make a difference, but arm64 uses a different calling convention for varargs
    // it is therefore important to not call objc_msgSend as a vararg function
    return new ID(myObjcMsgSend.invokeLong(prepInvoke(id, selector, args)));
  }

  /**
   * Invokes the given vararg selector.
   * Expects `NSArray arrayWithObjects:(id), ...` like signature, i.e. exactly one fixed argument, followed by varargs.
   */
  public static ID invokeVarArg(final ID id, final Pointer selector, Object... args) {
    // c functions and objc methods have at least 1 fixed argument, we therefore need to separate out the first argument
    return myFoundationLibrary.objc_msgSend(id, selector, args[0], Arrays.copyOfRange(args, 1, args.length));
  }

  public static ID invoke(final String cls, final String selector, Object... args) {
    return invoke(getObjcClass(cls), createSelector(selector), args);
  }

  public static ID invokeVarArg(final String cls, final String selector, Object... args) {
    return invokeVarArg(getObjcClass(cls), createSelector(selector), args);
  }

  public static ID safeInvoke(final String stringCls, final String stringSelector, Object... args) {
    ID cls = getObjcClass(stringCls);
    Pointer selector = createSelector(stringSelector);
    if (!invoke(cls, "respondsToSelector:", selector).booleanValue()) {
      throw new RuntimeException(String.format("Missing selector %s for %s", stringSelector, stringCls));
    }
    return invoke(cls, selector, args);
  }

  public static ID invoke(final ID id, final String selector, Object... args) {
    return invoke(id, createSelector(selector), args);
  }

  public static boolean isNil(ID id) {
    return id == null || ID.NIL.equals(id);
  }

  public static ID safeInvoke(final ID id, final String stringSelector, Object... args) {
    Pointer selector = createSelector(stringSelector);
    if (!id.equals(ID.NIL) && !invoke(id, "respondsToSelector:", selector).booleanValue()) {
      throw new RuntimeException(String.format("Missing selector %s for %s", stringSelector, toStringViaUTF8(invoke(id, "description"))));
    }
    return invoke(id, selector, args);
  }

  public static ID allocateObjcClassPair(ID superCls, String name) {
    return myFoundationLibrary.objc_allocateClassPair(superCls, name, 0);
  }

  public static void registerObjcClassPair(ID cls) {
    myFoundationLibrary.objc_registerClassPair(cls);
  }

  public static boolean isClassRespondsToSelector(ID cls, Pointer selectorName) {
    return myFoundationLibrary.class_respondsToSelector(cls, selectorName);
  }

  /**
   * @param cls          The class to which to add a method.
   * @param selectorName A selector that specifies the name of the method being added.
   * @param impl         A function which is the implementation of the new method. The function must take at least two arguments-self and _cmd.
   * @param types        An array of characters that describe the types of the arguments to the method.
   *                     See <a href="https://developer.apple.com/library/IOs/documentation/Cocoa/Conceptual/ObjCRuntimeGuide/Articles/ocrtTypeEncodings.html#//apple_ref/doc/uid/TP40008048-CH100"></a>
   * @return true if the method was added successfully, otherwise false (for example, the class already contains a method implementation with that name).
   */
  public static boolean addMethod(ID cls, Pointer selectorName, Callback impl, String types) {
    return myFoundationLibrary.class_addMethod(cls, selectorName, impl, types);
  }

  public static boolean addIvar(ID cls, String name, String types) {
    return myFoundationLibrary.class_addIvar(cls, name, Native.LONG_SIZE, ((Double) Math.log(Native.LONG_SIZE)).intValue(), types);
  }

  public static Pointer getInstanceVariable(ID cls, String name) {
    return myFoundationLibrary.class_getInstanceVariable(cls, name);
  }

  public static boolean setIvar(ID instance, Pointer ivar, ID value) {
    return myFoundationLibrary.object_setIvar(instance, ivar, value);
  }

  public static ID getIvar(ID instance, Pointer ivar) {
    return myFoundationLibrary.object_getIvar(instance, ivar);
  }

  public static boolean addProtocol(ID aClass, ID protocol) {
    return myFoundationLibrary.class_addProtocol(aClass, protocol);
  }

  public static boolean addMethodByID(ID cls, Pointer selectorName, ID impl, String types) {
    return myFoundationLibrary.class_addMethod(cls, selectorName, impl, types);
  }

  public static boolean isMetaClass(ID cls) {
    return myFoundationLibrary.class_isMetaClass(cls);
  }

  public static String stringFromSelector(Pointer selector) {
    ID id = myFoundationLibrary.NSStringFromSelector(selector);
    return ID.NIL.equals(id) ? null : toStringViaUTF8(id);
  }

  public static String stringFromClass(ID aClass) {
    ID id = myFoundationLibrary.NSStringFromClass(aClass);
    return ID.NIL.equals(id) ? null : toStringViaUTF8(id);
  }

  public static Pointer getClass(Pointer clazz) {
    return myFoundationLibrary.objc_getClass(clazz);
  }

  public static String fullUserName() {
    return toStringViaUTF8(myFoundationLibrary.NSFullUserName());
  }

  public static ID class_replaceMethod(ID cls, Pointer selector, Callback impl, String types) {
    return myFoundationLibrary.class_replaceMethod(cls, selector, impl, types);
  }

  public static ID getMetaClass(String className) {
    return myFoundationLibrary.objc_getMetaClass(className);
  }

  public static boolean isPackageAtPath(final String path) {
    final ID workspace = invoke("NSWorkspace", "sharedWorkspace");
    final ID result = invoke(workspace, createSelector("isFilePackageAtPath:"), nsString(path));

    return result.booleanValue();
  }

  public static boolean isPackageAtPath(final File file) {
    if (!file.isDirectory()) return false;
    return isPackageAtPath(file.getPath());
  }

  public static ID nsString(String s) {
    return s == null ? ID.NIL : NSString.create(s);
  }

  public static ID nsUUID(UUID uuid) {
    return nsUUID(uuid.toString());
  }

  public static ID nsUUID(String uuid) {
    return invoke(invoke(invoke("NSUUID", "alloc"), "initWithUUIDString:", nsString(uuid)), "autorelease");
  }

  public static String toStringViaUTF8(ID cfString) {
    if (ID.NIL.equals(cfString)) return null;

    int lengthInChars = myFoundationLibrary.CFStringGetLength(cfString);
    int potentialLengthInBytes = 3 * lengthInChars + 1; // UTF8 fully escaped 16 bit chars, plus nul

    byte[] buffer = new byte[potentialLengthInBytes];
    byte ok = myFoundationLibrary.CFStringGetCString(cfString, buffer, buffer.length, FoundationLibrary.kCFStringEncodingUTF8);
    if (ok == 0) throw new RuntimeException("Could not convert string");
    return Native.toString(buffer);
  }

  public static String getNSErrorText(ID error) {
    if (error == null || error.byteValue() == 0) return null;

    String description = toStringViaUTF8(invoke(error, "localizedDescription"));
    String recovery = toStringViaUTF8(invoke(error, "localizedRecoverySuggestion"));
    if (recovery != null) description += "\n" + recovery;
    return description;
  }

  public static String getEncodingName(long nsStringEncoding) {
    long cfEncoding = myFoundationLibrary.CFStringConvertNSStringEncodingToEncoding(nsStringEncoding);
    ID pointer = myFoundationLibrary.CFStringConvertEncodingToIANACharSetName(cfEncoding);
    String name = toStringViaUTF8(pointer);
    if ("macintosh".equals(name)) name = "MacRoman"; // JDK8 does not recognize IANA's "macintosh" alias
    return name;
  }

  public static long getEncodingCode(String encodingName) {
    if (encodingName == null || encodingName.trim().equals("")) return -1;

    ID converted = nsString(encodingName);
    long cfEncoding = myFoundationLibrary.CFStringConvertIANACharSetNameToEncoding(converted);

    ID restored = myFoundationLibrary.CFStringConvertEncodingToIANACharSetName(cfEncoding);
    if (ID.NIL.equals(restored)) return -1;

    return convertCFEncodingToNS(cfEncoding);
  }

  private static long convertCFEncodingToNS(long cfEncoding) {
    return myFoundationLibrary.CFStringConvertEncodingToNSStringEncoding(cfEncoding) & 0xffffffffffL;  // trim to C-type limits
  }

  public static void foreachCFArray(ID theArray, Consumer<ID> callback) {
    long count = myFoundationLibrary.CFArrayGetCount(theArray);
    for (long i = 0; i < count; i++) {
      callback.accept(myFoundationLibrary.CFArrayGetValueAtIndex(theArray, i));
    }
  }

  public static void cfRetain(ID id) {
    myFoundationLibrary.CFRetain(id);
  }

  public static void cfRelease(ID... ids) {
    for (ID id : ids) {
      if (id != null) {
        myFoundationLibrary.CFRelease(id);
      }
    }
  }

  public static ID autorelease(ID id) {
    return invoke(id, "autorelease");
  }

  public static boolean isMainThread() {
    return invoke("NSThread", "isMainThread").booleanValue();
  }

  public static void executeOnMainThread(final boolean withAutoreleasePool, final boolean waitUntilDone, final Runnable runnable) {
    String runnableCountString;
    synchronized (RUNNABLE_LOCK) {
      initRunnableSupport();

      runnableCountString = String.valueOf(++ourCurrentRunnableCount);
      ourMainThreadRunnables.put(runnableCountString, new RunnableInfo(runnable, withAutoreleasePool));
    }

    // fixme: Use Grand Central Dispatch instead?
    final ID ideaRunnable = getObjcClass("IdeaRunnable");
    final ID runnableObject = invoke(invoke(ideaRunnable, "alloc"), "init");
    final ID keyObject = invoke(nsString(runnableCountString), "retain");
    invoke(runnableObject, "performSelectorOnMainThread:withObject:waitUntilDone:", createSelector("run:"),
            keyObject, Boolean.valueOf(waitUntilDone));
    invoke(runnableObject, "release");
  }

  /**
   * Registers idea runnable adapter class in ObjC runtime, if not registered yet.
   * <p>
   * Warning: NOT THREAD-SAFE! Must be called under lock. Danger of segmentation fault.
   */
  private static void initRunnableSupport() {
    if (ourRunnableCallback == null) {
      final ID runnableClass = allocateObjcClassPair(getObjcClass("NSObject"), "IdeaRunnable");
      registerObjcClassPair(runnableClass);

      final Callback callback = new Callback() {
        @SuppressWarnings("UnusedDeclaration")
        public void callback(ID self, String selector, ID keyObject) {
          final String key = toStringViaUTF8(keyObject);
          invoke(keyObject, "release");

          RunnableInfo info;
          synchronized (RUNNABLE_LOCK) {
            info = ourMainThreadRunnables.remove(key);
          }

          if (info == null) {
            return;
          }

          ID pool = null;
          try {
            if (info.myUseAutoreleasePool) {
              pool = invoke("NSAutoreleasePool", "new");
            }

            info.myRunnable.run();
          } finally {
            if (pool != null) {
              invoke(pool, "release");
            }
          }
        }
      };
      if (!addMethod(runnableClass, createSelector("run:"), callback, "v@:*")) {
        throw new RuntimeException("Unable to add method to objective-c runnableClass class!");
      }
      ourRunnableCallback = callback;
    }
  }

  public static ID fillArray(final Object[] a) {
    final ID result = invoke("NSMutableArray", "array");
    for (Object s : a) {
      invoke(result, "addObject:", convertType(s));
    }

    return result;
  }

  public static ID createDict(final String[] keys, final Object[] values) {
    final ID nsKeys = invokeVarArg("NSArray", "arrayWithObjects:", convertTypes(keys));
    final ID nsData = invokeVarArg("NSArray", "arrayWithObjects:", convertTypes(values));
    return invoke("NSDictionary", "dictionaryWithObjects:forKeys:", nsData, nsKeys);
  }

  public static PointerType createPointerReference() {
    PointerType reference = new PointerByReference(new Memory(Native.POINTER_SIZE));
    reference.getPointer().clear(Native.POINTER_SIZE);
    return reference;
  }

  public static ID castPointerToNSError(PointerType pointerType) {
    return new ID(pointerType.getPointer().getLong(0));
  }

  public static Object[] convertTypes(Object[] v) {
    final Object[] result = new Object[v.length + 1];
    for (int i = 0; i < v.length; i++) {
      result[i] = convertType(v[i]);
    }
    result[v.length] = ID.NIL;
    return result;
  }

  private static Object convertType(Object o) {
    if (o instanceof Pointer || o instanceof ID) {
      return o;
    } else if (o instanceof String) {
      return nsString((String) o);
    } else {
      throw new IllegalArgumentException("Unsupported type! " + o.getClass());
    }
  }

  private static final class NSString {
    private static final ID nsStringCls = getObjcClass("NSString");
    private static final Pointer stringSel = createSelector("string");
    private static final Pointer allocSel = createSelector("alloc");
    private static final Pointer autoreleaseSel = createSelector("autorelease");
    private static final Pointer initWithBytesLengthEncodingSel = createSelector("initWithBytes:length:encoding:");
    private static final long nsEncodingUTF16LE = convertCFEncodingToNS(FoundationLibrary.kCFStringEncodingUTF16LE);


    public static ID create(String s) {
      // Use a byte[] rather than letting jna do the String -> char* marshalling itself.
      // Turns out about 10% quicker for long strings.
      if (s.isEmpty()) {
        return invoke(nsStringCls, stringSel);
      }

      byte[] utf16Bytes = s.getBytes(StandardCharsets.UTF_16LE);
      return invoke(invoke(invoke(nsStringCls, allocSel),
              initWithBytesLengthEncodingSel, utf16Bytes, utf16Bytes.length, nsEncodingUTF16LE),
              autoreleaseSel);
    }
  }

  static class RunnableInfo {
    Runnable myRunnable;
    boolean myUseAutoreleasePool;
    RunnableInfo(Runnable runnable, boolean useAutoreleasePool) {
      myRunnable = runnable;
      myUseAutoreleasePool = useAutoreleasePool;
    }
  }

  public static class NSDictionary {
    private final ID myDelegate;

    public NSDictionary(ID delegate) {
      myDelegate = delegate;
    }

    public static Map<String, String> toStringMap(ID delegate) {
      Map<String, String> result = new HashMap<>();
      if (isNil(delegate)) {
        return result;
      }

      NSDictionary dict = new NSDictionary(delegate);
      NSArray keys = dict.keys();

      for (int i = 0; i < keys.count(); i++) {
        String key = toStringViaUTF8(keys.at(i));
        String val = toStringViaUTF8(dict.get(key));
        result.put(key, val);
      }

      return result;
    }

    public static ID toStringDictionary(Map<String, String> map) {
      ID dict = invoke("NSMutableDictionary", "dictionaryWithCapacity:", map.size());
      for (Map.Entry<String, String> entry : map.entrySet()) {
        invoke(dict, "setObject:forKey:", nsString(entry.getValue()), nsString(entry.getKey()));
      }
      return dict;
    }

    public ID get(ID key) {
      return invoke(myDelegate, "objectForKey:", key);
    }

    public ID get(String key) {
      return get(nsString(key));
    }

    public int count() {
      return invoke(myDelegate, "count").intValue();
    }

    public NSArray keys() {
      return new NSArray(invoke(myDelegate, "allKeys"));
    }
  }

  public static class NSArray {
    private final ID myDelegate;

    public NSArray(ID delegate) {
      myDelegate = delegate;
    }

    public int count() {
      return invoke(myDelegate, "count").intValue();
    }

    public ID at(int index) {
      return invoke(myDelegate, "objectAtIndex:", index);
    }


    public List<ID> getList() {
      List<ID> result = new ArrayList<>();
      for (int i = 0; i < count(); i++) {
        result.add(at(i));
      }
      return result;
    }
  }

  public static class NSAutoreleasePool {
    private final ID myDelegate;

    public NSAutoreleasePool() {
      myDelegate = invoke(invoke("NSAutoreleasePool", "alloc"), "init");
    }

    public void drain() {
      invoke(myDelegate, "drain");
    }
  }

  public static class CGFloat implements NativeMapped {
    private final double value;

    @SuppressWarnings("UnusedDeclaration")
    public CGFloat() {
      this(0);
    }

    public CGFloat(double d) {
      value = d;
    }

    @Override
    public Object fromNative(Object o, FromNativeContext fromNativeContext) {
      switch (Native.LONG_SIZE) {
        case 4:
          return new CGFloat((Float) o);
        case 8:
          return new CGFloat((Double) o);
      }
      throw new IllegalStateException();
    }

    @Override
    public Object toNative() {
      switch (Native.LONG_SIZE) {
        case 4:
          return (float) value;
        case 8:
          return value;
      }
      throw new IllegalStateException();
    }

    @Override
    public Class<?> nativeType() {
      switch (Native.LONG_SIZE) {
        case 4:
          return Float.class;
        case 8:
          return Double.class;
      }
      throw new IllegalStateException();
    }
  }
}