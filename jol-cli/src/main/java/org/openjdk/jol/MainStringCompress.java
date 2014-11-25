/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jol;

import org.openjdk.jol.datamodel.DataModel;
import org.openjdk.jol.datamodel.X86_32_DataModel;
import org.openjdk.jol.datamodel.X86_64_COOPS_DataModel;
import org.openjdk.jol.datamodel.X86_64_DataModel;
import org.openjdk.jol.heap.HeapDumpReader;
import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldData;
import org.openjdk.jol.layouters.HotSpotLayouter;
import org.openjdk.jol.layouters.Layouter;
import org.openjdk.jol.util.Multiset;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.lang.System.out;

/**
 * @author Aleksey Shipilev
 */
public class MainStringCompress {

    static final DataModel[] DATA_MODELS = new DataModel[]{
            new X86_32_DataModel(),
            new X86_64_DataModel(),
            new X86_64_COOPS_DataModel(),
            new X86_64_COOPS_DataModel(16)
    };

    static long stringID;
    static int stringValueIdx;
    static int stringValueSize;

    static Multiset<Integer> compressibleCharArrays;
    static Multiset<Integer> nonCompressibleCharArrays;
    static String path;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: jol-string-compress.jar [heapdump.hprof]");
            System.exit(1);
        }
        path = args[0];

        compressibleCharArrays = new Multiset<Integer>();
        nonCompressibleCharArrays = new Multiset<Integer>();

        final Set<Long> referencedArrays = new HashSet<Long>();

        HeapDumpReader preReader = new HeapDumpReader(new File(path)) {
            @Override
            protected void visitClass(long id, String name, List<Integer> oopIdx, int oopSize) {
                if (name.equals("java/lang/String")) {
                    stringID = id;
                    stringValueIdx = oopIdx.get(0);
                    stringValueSize = oopSize;
                }
            }

            @Override
            protected void visitInstance(long id, long klassID, byte[] bytes) {
                if (stringID == 0) {
                    throw new IllegalStateException("java/lang/String was not discovered yet");
                }
                if (klassID == stringID) {
                    ByteBuffer wrap = ByteBuffer.wrap(bytes);
                    switch (stringValueSize) {
                        case 4:
                            referencedArrays.add((long) wrap.getInt(stringValueIdx));
                            break;
                        case 8:
                            referencedArrays.add(wrap.getLong(stringValueIdx));
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                }
            }
        };
        preReader.parse();

        HeapDumpReader reader = new HeapDumpReader(new File(path)) {
            @Override
            protected void visitPrimArray(long id, String typeClass, int count, byte[] bytes) {
                if (referencedArrays.contains(id)) {
                    if (isCompressible(bytes)) {
                        compressibleCharArrays.add(bytes.length);
                    } else {
                        nonCompressibleCharArrays.add(bytes.length);
                    }
                }
            }
        };

        Multiset<ClassData> data = reader.parse();

        out.printf("\"%12s\", \"%12s\", \"%12s\", \"%12s\", \"%12s\", \"%12s\", \"%12s\", \"%12s\", \"%s\", \"%s\"%n",
                "total", "String", "String+bool", "String+oop", "1-byte char[]",
                "2-byte char[]", "savings(bool)", "savings(oop)", "hprof file", "model");

        for (DataModel model : DATA_MODELS) {
            printLine(data, new HotSpotLayouter(model, false, false, false));
        }
    }

    private static void printLine(Multiset<ClassData> data, Layouter l) {
        long strings = 0;
        long stringsBool = 0;
        long stringsOop = 0;

        long totalFootprint = 0;
        for (ClassData cd : data.keys()) {
            int count = data.count(cd);

            if (cd.name().equals("java/lang/String")) {
                ClassData mcd = ClassData.parseClass(String.class);
                strings += l.layout(mcd).instanceSize() * count;

                ClassData mcdBool = ClassData.parseClass(String.class);
                mcdBool.addField(FieldData.create("String", "isCompressed", "boolean"));
                stringsBool += l.layout(mcdBool).instanceSize() * count;

                ClassData mcdOop = ClassData.parseClass(String.class);
                mcdOop.addField(FieldData.create("String", "coder", "java/lang/Object"));
                stringsOop += l.layout(mcdOop).instanceSize() * count;
            } else {
                totalFootprint += l.layout(cd).instanceSize() * count;
            }
        }

        int savings = 0;
        int compressibleBytes = 0;
        for (Integer len : compressibleCharArrays.keys()) {
            int count = compressibleCharArrays.count(len);

            ClassData charArr = new ClassData("char[]", "char", len);
            ClassData byteArr = new ClassData("byte[]", "byte", len);

            savings += (l.layout(charArr).instanceSize() - l.layout(byteArr).instanceSize()) * count;
            compressibleBytes += l.layout(charArr).instanceSize() * count;
        }

        int nonCompressibleBytes = 0;
        for (Integer len : nonCompressibleCharArrays.keys()) {
            ClassData charArr = new ClassData("char[]", "char", len);
            nonCompressibleBytes += l.layout(charArr).instanceSize() * nonCompressibleCharArrays.count(len);
        }

        totalFootprint += strings;

        double savingBool = 100.0 * (savings - (stringsBool - strings)) / totalFootprint;
        double savingOop  = 100.0 * (savings - (stringsOop  - strings)) / totalFootprint;
        out.printf("%14d, %14d, %14d, %14d, %14d, %14d, %14.3f, %14.3f, \"%s\", \"%s\"%n",
                totalFootprint, strings, stringsBool, stringsOop, compressibleBytes, nonCompressibleBytes,
                savingBool, savingOop, path, l);
    }

    public static boolean isCompressible(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int c = 0; c < bytes.length; c += 2) {
            if ((buf.getShort(c) & 0xFF00) != 0) {
                return false;
            }
        }
        return true;
    }
}