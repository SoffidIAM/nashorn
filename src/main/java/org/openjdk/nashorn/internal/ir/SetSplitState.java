/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.nashorn.internal.codegen.CompilerConstants;
import org.openjdk.nashorn.internal.ir.visitor.NodeVisitor;
import org.openjdk.nashorn.internal.runtime.Scope;

/**
 * Synthetic AST node that represents loading of the scope object and invocation of the {@link Scope#setSplitState(int)}
 * method on it. It has no JavaScript source representation and only occurs in synthetic functions created by
 * the split-into-functions transformation.
 */
public final class SetSplitState extends Statement {
    private static final long serialVersionUID = 1L;

    private final int state;

    /**
     * Creates a new split state setter
     * @param state the state to set
     * @param lineNumber the line number where it is inserted
     */
    public SetSplitState(final int state, final int lineNumber) {
        super(lineNumber, NO_TOKEN, NO_FINISH);
        this.state = state;
    }

    /**
     * Returns the state this setter sets.
     * @return the state this setter sets.
     */
    public int getState() {
        return state;
    }

    @Override
    public Node accept(final NodeVisitor<? extends LexicalContext> visitor) {
        return visitor.enterSetSplitState(this) ? visitor.leaveSetSplitState(this) : this;
    }

    @Override
    public void toString(final StringBuilder sb, final boolean printType) {
        sb.append(CompilerConstants.SCOPE.symbolName()).append('.').append(Scope.SET_SPLIT_STATE.name())
        .append('(').append(state).append(");");
    }
}
