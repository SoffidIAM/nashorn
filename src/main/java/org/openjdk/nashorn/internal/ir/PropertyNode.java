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

package org.openjdk.nashorn.internal.ir;

import org.openjdk.nashorn.internal.ir.annotations.Immutable;
import org.openjdk.nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of an object literal property.
 */
@Immutable
public final class PropertyNode extends Node {
    private static final long serialVersionUID = 1L;

    /** Property key. */
    private final Expression key;

    /** Property value. */
    private final Expression value;

    /** Property getter. */
    private final FunctionNode getter;

    /** Property getter. */
    private final FunctionNode setter;

    /** static property flag */
    private final boolean isStatic;

    /** Computed property flag */
    private final boolean computed;

    /**
     * Constructor
     *
     * @param token   token
     * @param finish  finish
     * @param key     the key of this property
     * @param value   the value of this property
     * @param getter  getter function body
     * @param setter  setter function body
     * @param isStatic is this a static property?
     * @param computed is this a computed property?
     */
    public PropertyNode(final long token, final int finish, final Expression key, final Expression value, final FunctionNode getter, final FunctionNode setter, final boolean isStatic, final boolean computed) {
        super(token, finish);
        this.key    = key;
        this.value  = value;
        this.getter = getter;
        this.setter = setter;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    private PropertyNode(final PropertyNode propertyNode, final Expression key, final Expression value, final FunctionNode getter, final FunctionNode setter, final boolean isStatic, final boolean computed) {
        super(propertyNode);
        this.key    = key;
        this.value  = value;
        this.getter = getter;
        this.setter = setter;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    /**
     * Get the name of the property key, or {@code null} if key is a computed name.
     * @return key name or null
     */
    public String getKeyName() {
        return !computed && key instanceof PropertyKey ? ((PropertyKey) key).getPropertyName() : null;
    }

    @Override
    public Node accept(final NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterPropertyNode(this)) {
            return visitor.leavePropertyNode(
                setKey((Expression) key.accept(visitor)).
                setValue(value == null ? null : (Expression)value.accept(visitor)).
                setGetter(getter == null ? null : (FunctionNode)getter.accept(visitor)).
                setSetter(setter == null ? null : (FunctionNode)setter.accept(visitor)));
        }

        return this;
    }

    @Override
    public void toString(final StringBuilder sb, final boolean printType) {
        if (value instanceof FunctionNode && ((FunctionNode)value).getIdent() != null) {
            value.toString(sb);
        }

        if (value != null) {
            key.toString(sb, printType);
            sb.append(": ");
            value.toString(sb, printType);
        }

        if (getter != null) {
            sb.append(' ');
            getter.toString(sb, printType);
        }

        if (setter != null) {
            sb.append(' ');
            setter.toString(sb, printType);
        }
    }

    /**
     * Get the getter for this property
     * @return getter or null if none exists
     */
    public FunctionNode getGetter() {
        return getter;
    }

    /**
     * Set the getter of this property, null if none
     * @param getter getter
     * @return same node or new node if state changed
     */
    public PropertyNode setGetter(final FunctionNode getter) {
        if (this.getter == getter) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Return the key for this property node
     * @return the key
     */
    public Expression getKey() {
        return key;
    }

    private PropertyNode setKey(final Expression key) {
        if (this.key == key) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Get the setter for this property
     * @return setter or null if none exists
     */
    public FunctionNode getSetter() {
        return setter;
    }

    /**
     * Set the setter for this property, null if none
     * @param setter setter
     * @return same node or new node if state changed
     */
    public PropertyNode setSetter(final FunctionNode setter) {
        if (this.setter == setter) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Get the value of this property
     * @return property value
     */
    public Expression getValue() {
        return value;
    }

    /**
     * Set the value of this property
     * @param value new value
     * @return same node or new node if state changed
     */
    public PropertyNode setValue(final Expression value) {
        if (this.value == value) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Returns true if this is a static property.
     *
     * @return true if static flag is set
     */
    public boolean isStatic() {
        return isStatic;
    }

    /**
     * Returns true if this is a computed property.
     *
     * @return true if the computed flag is set
     */
    public boolean isComputed() {
        return computed;
    }
}
