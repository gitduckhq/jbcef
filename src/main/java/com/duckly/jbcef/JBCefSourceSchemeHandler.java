// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.duckly.jbcef;

import com.intellij.openapi.Disposable;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.network.CefRequest;

import static com.duckly.jbcef.JBCefSourceSchemeHandlerFactory.SOURCE_SCHEME;

class JBCefSourceSchemeHandler extends CefResourceHandlerAdapter implements Disposable {
  @Override
  public void dispose() {}

  @Override
  public boolean processRequest(CefRequest request, CefCallback callback) {
    String url = request.getURL();
    if (url == null || !url.startsWith(SOURCE_SCHEME)) return false;
    if (JBCefPsiNavigationUtils.INSTANCE.navigateTo(url)) {
      callback.Continue();
      return true;
    }
    return false;
  }
}
