/*
 * Copyright 2019 Zhou Pengfei
 * SPDX-License-Identifier: Apache-2.0
 */

package org.GenZapp.glide.common.loader;

import org.GenZapp.glide.common.io.Reader;
import org.GenZapp.glide.common.io.StreamReader;

import java.io.IOException;
import java.io.InputStream;

/**
 * @Author: pengfei.zhou
 * @CreateDate: 2019/3/28
 */
public abstract class StreamLoader implements Loader {
    protected abstract InputStream getInputStream() throws IOException;


    public final synchronized Reader obtain() throws IOException {
        return new StreamReader(getInputStream());
    }
}
