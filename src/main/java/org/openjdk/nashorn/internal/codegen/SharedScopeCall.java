/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.nashorn.internal.codegen;

import static org.openjdk.nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;
import static org.openjdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_OPTIMISTIC;

import java.util.Arrays;
import java.util.EnumSet;
import org.openjdk.nashorn.internal.codegen.types.Type;
import org.openjdk.nashorn.internal.ir.Symbol;
import org.openjdk.nashorn.internal.runtime.ScriptObject;
import org.openjdk.nashorn.internal.runtime.UnwarrantedOptimismException;
import org.openjdk.nashorn.internal.runtime.options.Options;

/**
 * A scope call or get operation that can be shared by several call sites. This generates a static
 * method that wraps the invokedynamic instructions to get or call scope variables.
 * The reason for this is to reduce memory footprint and initial linking overhead of huge scripts.
 *
 * <p>Static methods generated by this class expect three parameters in addition to the parameters of the
 * function call: The current scope object, the depth of the target scope relative to the scope argument,
 * and the program point in case the target operation is optimistic.</p>
 *
 * <p>Optimistic operations are called with program point <code>0</code>. If an <code>UnwarrentedOptimismException</code>
 * is thrown, it is caught by the shared call method and rethrown with the program point of the invoking call site.</p>
 *
 * <p>Shared scope calls are not used if the scope contains a <code>with</code> statement or a call to
 * <code>eval</code>.</p>
 */
class SharedScopeCall {

    /**
     * Threshold for using shared scope function calls.
     */
    public static final int SHARED_CALL_THRESHOLD =
            Options.getIntProperty("nashorn.shared.scope.call.threshold", 5);
    /**
     * Threshold for using shared scope variable getter. This is higher than for calls as lower values
     * degrade performance on many scripts.
     */
    public static final int SHARED_GET_THRESHOLD  =
            Options.getIntProperty("nashorn.shared.scope.get.threshold", 100);

    private static final CompilerConstants.Call REPLACE_PROGRAM_POINT = virtualCallNoLookup(
                    UnwarrantedOptimismException.class, "replaceProgramPoint",
                    UnwarrantedOptimismException.class, int.class);

    /** Number of fixed parameters */
    private static final int FIXED_PARAM_COUNT = 3;

    private final Type valueType;
    private final Symbol symbol;
    private final Type returnType;
    private final Type[] paramTypes;
    private final int flags;
    private final boolean isCall;
    private final boolean isOptimistic;
    private CompileUnit compileUnit;
    private String methodName;
    private String staticSignature;

    /**
     * Constructor.
     *
     * @param symbol the symbol
     * @param valueType the type of the value
     * @param returnType the return type
     * @param paramTypes the function parameter types
     * @param flags the callsite flags
     * @param isOptimistic whether target call is optimistic and we need to handle UnwarrentedOptimismException
     */
    SharedScopeCall(final Symbol symbol, final Type valueType, final Type returnType, final Type[] paramTypes,
                    final int flags, final boolean isOptimistic) {
        this.symbol = symbol;
        this.valueType = valueType;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.flags = flags;
        this.isCall = paramTypes != null; // If paramTypes is not null this is a call, otherwise it's just a get.
        this.isOptimistic = isOptimistic;
    }

    @Override
    public int hashCode() {
        return symbol.hashCode() ^ returnType.hashCode() ^ Arrays.hashCode(paramTypes) ^ flags ^ Boolean.hashCode(isOptimistic);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof SharedScopeCall) {
            final SharedScopeCall c = (SharedScopeCall) obj;
            return symbol.equals(c.symbol)
                    && flags == c.flags
                    && returnType.equals(c.returnType)
                    && Arrays.equals(paramTypes, c.paramTypes)
                    && isOptimistic == c.isOptimistic;
        }
        return false;
    }

    /**
     * Set the compile unit and method name.
     * @param compileUnit the compile unit
     * @param methodName the method name
     */
    protected void setClassAndName(final CompileUnit compileUnit, final String methodName) {
        this.compileUnit = compileUnit;
        this.methodName  = methodName;
    }

    /**
     * Generate the invoke instruction for this shared scope call.
     * @param method the method emitter
     */
    public void generateInvoke(final MethodEmitter method) {
        method.invokestatic(compileUnit.getUnitClassName(), methodName, getStaticSignature());
    }

    /**
     * Generate the method that implements the scope get or call.
     */
    protected void generateScopeCall() {
        final ClassEmitter classEmitter = compileUnit.getClassEmitter();
        final EnumSet<ClassEmitter.Flag> methodFlags = EnumSet.of(ClassEmitter.Flag.STATIC);

        // This method expects two fixed parameters in addition to any parameters that may be
        // passed on to the function: A ScriptObject representing the caller's current scope object,
        // and an int specifying the distance to the target scope containing the symbol we want to
        // access, or -1 if this is not known at compile time (e.g. because of a "with" or "eval").

        final MethodEmitter method = classEmitter.method(methodFlags, methodName, getStaticSignature());
        method.begin();

        // Load correct scope by calling getProto(int) on the scope argument with the supplied depth argument
        method.load(Type.OBJECT, 0);
        method.load(Type.INT, 1);
        method.invoke(ScriptObject.GET_PROTO_DEPTH);

        assert !isCall || valueType.isObject(); // Callables are always loaded as object

        // Labels for catch of UnsupportedOptimismException
        final Label beginTry;
        final Label endTry;
        final Label catchLabel;

        if(isOptimistic) {
            beginTry = new Label("begin_try");
            endTry = new Label("end_try");
            catchLabel = new Label("catch_label");
            method.label(beginTry);
            method._try(beginTry, endTry, catchLabel, UnwarrantedOptimismException.class, false);
        } else {
            beginTry = endTry = catchLabel = null;
        }

        // If this is an optimistic get we set the optimistic flag but don't set the program point,
        // which implies a program point of 0. If optimism fails we'll replace it with the actual
        // program point which caller supplied as third argument.
        final int getFlags = isOptimistic && !isCall ? flags | CALLSITE_OPTIMISTIC : flags;
        method.dynamicGet(valueType, symbol.getName(), getFlags, isCall, false);

        // If this is a get we're done, otherwise call the value as function.
        if (isCall) {
            method.convert(Type.OBJECT);
            // ScriptFunction will see CALLSITE_SCOPE and will bind scope accordingly.
            method.loadUndefined(Type.OBJECT);
            int slot = FIXED_PARAM_COUNT;
            for (final Type type : paramTypes) {
                method.load(type, slot);
                slot += type.getSlots();
            }

            // Same as above, set optimistic flag but leave program point as 0.
            final int callFlags = isOptimistic ? flags | CALLSITE_OPTIMISTIC : flags;

            method.dynamicCall(returnType, 2 + paramTypes.length, callFlags, symbol.getName());

        }

        if (isOptimistic) {
            method.label(endTry);
        }

        method._return(returnType);

        if (isOptimistic) {
            // We caught a UnwarrantedOptimismException, replace 0 program point with actual program point
            method._catch(catchLabel);
            method.load(Type.INT, 2);
            method.invoke(REPLACE_PROGRAM_POINT);
            method.athrow();
        }

        method.end();
    }

    private String getStaticSignature() {
        if (staticSignature == null) {
            if (paramTypes == null) {
                staticSignature = Type.getMethodDescriptor(returnType, Type.typeFor(ScriptObject.class), Type.INT, Type.INT);
            } else {
                final Type[] params = new Type[paramTypes.length + FIXED_PARAM_COUNT];
                params[0] = Type.typeFor(ScriptObject.class);
                params[1] = Type.INT;
                params[2] = Type.INT;
                System.arraycopy(paramTypes, 0, params, FIXED_PARAM_COUNT, paramTypes.length);
                staticSignature = Type.getMethodDescriptor(returnType, params);
            }
        }
        return staticSignature;
    }

    @Override
    public String toString() {
        return methodName + " " + staticSignature;
    }

}
