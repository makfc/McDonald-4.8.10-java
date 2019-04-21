package com.tencent.p050mm.sdk.modelbiz;

import android.os.Bundle;
import com.tencent.p050mm.sdk.modelbase.BaseReq;
import com.tencent.p050mm.sdk.modelbase.BaseResp;
import java.util.LinkedList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.json.JSONTokener;

/* renamed from: com.tencent.mm.sdk.modelbiz.AddCardToWXCardPackage */
public class AddCardToWXCardPackage {

    /* renamed from: com.tencent.mm.sdk.modelbiz.AddCardToWXCardPackage$Req */
    public static class Req extends BaseReq {
        public List<WXCardItem> cardArrary;

        public boolean checkArgs() {
            if (this.cardArrary == null || this.cardArrary.size() == 0 || this.cardArrary.size() > 40) {
                return false;
            }
            for (WXCardItem wXCardItem : this.cardArrary) {
                if (wXCardItem == null || wXCardItem.cardId == null || wXCardItem.cardId.length() > 1024 || (wXCardItem.cardExtMsg != null && wXCardItem.cardExtMsg.length() > 1024)) {
                    return false;
                }
            }
            return true;
        }

        public int getType() {
            return 9;
        }

        public void toBundle(Bundle bundle) {
            super.toBundle(bundle);
            JSONStringer jSONStringer = new JSONStringer();
            try {
                jSONStringer.object();
                jSONStringer.key("card_list");
                jSONStringer.array();
                for (WXCardItem wXCardItem : this.cardArrary) {
                    jSONStringer.object();
                    jSONStringer.key("card_id");
                    jSONStringer.value(wXCardItem.cardId);
                    jSONStringer.key("card_ext");
                    jSONStringer.value(wXCardItem.cardExtMsg == null ? "" : wXCardItem.cardExtMsg);
                    jSONStringer.endObject();
                }
                jSONStringer.endArray();
                jSONStringer.endObject();
            } catch (Exception e) {
                e.printStackTrace();
            }
            bundle.putString("_wxapi_add_card_to_wx_card_list", jSONStringer.toString());
        }
    }

    /* renamed from: com.tencent.mm.sdk.modelbiz.AddCardToWXCardPackage$Resp */
    public static class Resp extends BaseResp {
        public List<WXCardItem> cardArrary;

        public Resp(Bundle bundle) {
            fromBundle(bundle);
        }

        public void fromBundle(Bundle bundle) {
            super.fromBundle(bundle);
            if (this.cardArrary == null) {
                this.cardArrary = new LinkedList();
            }
            String string = bundle.getString("_wxapi_add_card_to_wx_card_list");
            if (string != null && string.length() > 0) {
                try {
                    JSONArray jSONArray = ((JSONObject) new JSONTokener(string).nextValue()).getJSONArray("card_list");
                    for (int i = 0; i < jSONArray.length(); i++) {
                        JSONObject jSONObject = jSONArray.getJSONObject(i);
                        WXCardItem wXCardItem = new WXCardItem();
                        wXCardItem.cardId = jSONObject.optString("card_id");
                        wXCardItem.cardExtMsg = jSONObject.optString("card_ext");
                        wXCardItem.cardState = jSONObject.optInt("is_succ");
                        this.cardArrary.add(wXCardItem);
                    }
                } catch (Exception e) {
                }
            }
        }

        public int getType() {
            return 9;
        }
    }

    /* renamed from: com.tencent.mm.sdk.modelbiz.AddCardToWXCardPackage$WXCardItem */
    public static final class WXCardItem {
        public String cardExtMsg;
        public String cardId;
        public int cardState;
    }
}