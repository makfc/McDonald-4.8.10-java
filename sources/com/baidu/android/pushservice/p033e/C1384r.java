package com.baidu.android.pushservice.p033e;

import android.content.Context;
import com.baidu.android.pushservice.p036h.C1425a;
import java.util.HashMap;

/* renamed from: com.baidu.android.pushservice.e.r */
public class C1384r extends C1363c {
    /* renamed from: d */
    protected String f4848d;

    public C1384r(C1378l c1378l, Context context, String str) {
        super(c1378l, context);
        this.f4848d = str;
    }

    /* Access modifiers changed, original: protected */
    /* renamed from: a */
    public void mo13722a(HashMap<String, String> hashMap) {
        super.mo13722a((HashMap) hashMap);
        hashMap.put("method", "gunbind");
        hashMap.put("gid", this.f4848d);
        C1425a.m6442c("Gunbind", "Gunbind param -- " + C1370b.m6202a((HashMap) hashMap));
    }
}
