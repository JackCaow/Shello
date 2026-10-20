#include <jni.h>
#include <ghostty/vt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FN(name) Java_dev_ghostty_android_NativeBridge_##name
typedef struct {
    GhosttyTerminal terminal;
    GhosttyRenderState render;
    uint8_t replies[65536]; size_t reply_len; bool reply_overflow;
} AndroidTerminal;
static AndroidTerminal *lab(jlong h) { return (AndroidTerminal *)(intptr_t)h; }
static GhosttyTerminal term(jlong h) { return lab(h)->terminal; }
static void reply(GhosttyTerminal t, void *user, const uint8_t *data, size_t len) {
    AndroidTerminal *l=user;
    if (len > sizeof(l->replies)-l->reply_len) { l->reply_overflow=true; return; }
    memcpy(l->replies+l->reply_len,data,len); l->reply_len+=len;
}
static jlong wrap(JNIEnv *env, GhosttyTerminal t) {
    if (!t) return 0;
    AndroidTerminal *l=calloc(1,sizeof(*l));
    if (!l) { ghostty_terminal_free(t); (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/OutOfMemoryError"),"Native terminal allocation"); return 0; }
    l->terminal=t;
    if (ghostty_render_state_new(NULL,&l->render)!=GHOSTTY_SUCCESS) { ghostty_terminal_free(t); free(l); return 0; }
    ghostty_terminal_set(t,GHOSTTY_TERMINAL_OPT_USERDATA,l);
    ghostty_terminal_set(t,GHOSTTY_TERMINAL_OPT_WRITE_PTY,(const void *)reply);
    return (jlong)(intptr_t)l;
}
static void check(JNIEnv *env, GhosttyResult r) {
    if (r == GHOSTTY_SUCCESS) return;
    char message[80]; snprintf(message, sizeof(message), "Ghostty returned %d", r);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}
/* Return UTF-16 boundaries so Java selection and shaping share Ghostty's Unicode
 * version, independent of the Android ICU/font version. Widths remain VT-owned. */
JNIEXPORT jintArray JNICALL FN(graphemeEnds)(JNIEnv *env, jclass cls, jstring text) {
    if (!text) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "text");
        return NULL;
    }
    jsize len = (*env)->GetStringLength(env, text);
    if (!len) return (*env)->NewIntArray(env, 0);
    const jchar *utf16 = (*env)->GetStringChars(env, text, NULL);
    if (!utf16) return NULL;
    uint32_t *cps = malloc((size_t)len * sizeof(*cps));
    jint *ends = malloc((size_t)len * sizeof(*ends));
    if (!cps || !ends) {
        free(cps); free(ends);
        (*env)->ReleaseStringChars(env, text, utf16);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "Grapheme boundaries");
        return NULL;
    }
    size_t count = 0;
    for (jsize i = 0; i < len;) {
        uint32_t cp = utf16[i++];
        if (cp >= 0xd800 && cp <= 0xdbff && i < len && utf16[i] >= 0xdc00 && utf16[i] <= 0xdfff) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + (utf16[i++] - 0xdc00);
        } else if (cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
        cps[count] = cp; ends[count++] = i;
    }
    (*env)->ReleaseStringChars(env, text, utf16);
    jsize groups = 0;
    for (size_t i = 0; i < count;) {
        i += ghostty_unicode_grapheme_width(cps + i, count - i, NULL);
        // In-place compaction is safe: groups never advances beyond i - 1.
        ends[groups++] = ends[i - 1];
    }
    jintArray result = (*env)->NewIntArray(env, groups);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, groups, ends);
    free(cps); free(ends);
    return result;
}
JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls, jint cols, jint rows) {
    GhosttyTerminal t = NULL;
    check(env, ghostty_terminal_new(NULL, &t, cols, rows));
    if (!t) return 0;
    size_t limit = 65536;
    check(env, ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit));
    size_t bytes = 4 * 1024 * 1024;
    check(env, ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &bytes));
    return wrap(env,t);
}
JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jclass cls, jlong h) { if(h){ghostty_render_state_free(lab(h)->render);ghostty_terminal_free(term(h));free(lab(h));} }
JNIEXPORT void JNICALL FN(write)(JNIEnv *env, jclass cls, jlong h, jbyteArray data) {
    jbyte *p = (*env)->GetByteArrayElements(env, data, NULL);
    if (!p) return;
    ghostty_terminal_vt_write(term(h), (uint8_t *)p, (*env)->GetArrayLength(env, data));
    (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
}
JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jclass cls, jlong h, jint cols, jint rows) {
    check(env, ghostty_terminal_resize(term(h), cols, rows, 0, 0));
}
JNIEXPORT void JNICALL FN(scroll)(JNIEnv *env, jclass cls, jlong h, jint delta) {
    GhosttyTerminalScrollViewport v = {.tag=GHOSTTY_SCROLL_VIEWPORT_DELTA, .value.delta=delta};
    ghostty_terminal_scroll_viewport(term(h), v);
}
static void json_cp(FILE *out, uint32_t cp) {
    if (cp > 0xffff) { cp -= 0x10000; fprintf(out,"\\u%04x\\u%04x",0xd800+(cp>>10),0xdc00+(cp&1023)); }
    else fprintf(out,"\\u%04x", cp);
}
static int rgb(GhosttyColorRgb c) { return (c.r<<16)|(c.g<<8)|c.b; }
static int color(GhosttyStyleColor c, const GhosttyRenderStateColors *colors, int fallback) {
    if (c.tag == GHOSTTY_STYLE_COLOR_RGB) return rgb(c.value.rgb);
    if (c.tag == GHOSTTY_STYLE_COLOR_PALETTE) return rgb(colors->palette[c.value.palette]);
    return fallback;
}
JNIEXPORT jstring JNICALL FN(frame)(JNIEnv *env, jclass cls, jlong h) {
    GhosttyTerminal t=term(h);
    ghostty_render_state_update(lab(h)->render,t);
    GhosttyRenderStateColors colors=GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    GhosttyRenderStateCursor cursor=GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(lab(h)->render,GHOSTTY_RENDER_STATE_DATA_COLORS,&colors);
    ghostty_render_state_get(lab(h)->render,GHOSTTY_RENDER_STATE_DATA_CURSOR,&cursor);
    uint16_t cols=0,rows=0,x=0,y=0; int active=0; bool mouse=false;
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_COLS,&cols); ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_CURSOR_X,&x); ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_CURSOR_Y,&y);
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&active);
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
    GhosttyTerminalScrollbar bar={0}; ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&bar);
    char *buf=NULL; size_t len=0; FILE *out=open_memstream(&buf,&len);
    if(!out){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/OutOfMemoryError"),"Diagnostic buffer");return NULL;}
    fprintf(out,"{\"cols\":%u,\"rows\":%u,\"x\":%u,\"y\":%u,\"alt\":%d,\"mouse\":%s,\"offset\":%llu,\"total\":%llu,\"cells\":[",cols,rows,x,y,active,mouse?"true":"false",(unsigned long long)bar.offset,(unsigned long long)bar.total);
    for(int row=0;row<rows;row++) {
        if(row) fputc(',',out); fputc('[',out);
        for(int col=0;col<cols;col++) {
            if(col) fputc(',',out);
            GhosttyGridRef ref=GHOSTTY_INIT_SIZED(GhosttyGridRef);
            GhosttyPoint pt={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=col,.y=row}};
            GhosttyResult r=ghostty_terminal_grid_ref(t,pt,&ref);
            if(r!=GHOSTTY_SUCCESS) { fputs("[\"\",0,0,1]",out); continue; }
            GhosttyCell cell; ghostty_grid_ref_cell(&ref,&cell);
            GhosttyCellWide wide; ghostty_cell_get(cell,GHOSTTY_CELL_DATA_WIDE,&wide);
            uint32_t cps[128]; size_t n=0;
            r=ghostty_grid_ref_graphemes(&ref,cps,128,&n);
            GhosttyStyle style=GHOSTTY_INIT_SIZED(GhosttyStyle); ghostty_grid_ref_style(&ref,&style);
            fputs("[\"",out);
            if(r==GHOSTTY_SUCCESS) for(size_t i=0;i<n;i++) json_cp(out,cps[i]);
            int fg=color(style.fg_color,&colors,rgb(colors.foreground)),bg=color(style.bg_color,&colors,rgb(colors.background));
            if(style.inverse){int swap=fg;fg=bg;bg=swap;}
            int flags=(style.bold?1:0)|(style.italic?2:0)|(style.faint?4:0)|(style.invisible?8:0)|(style.strikethrough?16:0)|(style.overline?32:0)|(style.blink?64:0);
            fprintf(out,"\",%d,%d,%d,%d,%d,%d]",fg,bg,(int)wide,flags,style.underline,color(style.underline_color,&colors,fg));
        }
        fputc(']',out);
    }
    fputs("],\"modes\":[",out);
    int modes[]={1,1000,1006,2004};
    for(int i=0;i<4;i++){GhosttyTerminalModeConfig m={.mode=ghostty_mode_new(modes[i],false)};ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_MODE,&m);fprintf(out,"%s%s",i?",":"",m.value?"true":"false");}
    fprintf(out,"],\"cursorVisible\":%s,\"cursorBlink\":%s,\"cursorShape\":%d,\"cursorColor\":%d,\"defaultBg\":%d}",
        cursor.visible&&cursor.viewport_has_value?"true":"false",cursor.blinking?"true":"false",cursor.visual_style,
        colors.cursor_has_value?rgb(colors.cursor):rgb(colors.foreground),rgb(colors.background)); fclose(out);
    jstring result=(*env)->NewStringUTF(env,buf); free(buf); return result;
}
JNIEXPORT jbyteArray JNICALL FN(snapshot)(JNIEnv *env,jclass cls,jlong h) {
    uint8_t *p=NULL;size_t n=0; GhosttyResult r=ghostty_snapshot_encode_alloc(term(h),NULL,&p,&n); check(env,r);
    if(r!=GHOSTTY_SUCCESS)return NULL;
    jbyteArray a=(*env)->NewByteArray(env,n); if(a)(*env)->SetByteArrayRegion(env,a,0,n,(jbyte*)p); ghostty_free(NULL,p,n);return a;
}
JNIEXPORT jlong JNICALL FN(restore)(JNIEnv *env,jclass cls,jbyteArray a) {
    jbyte *p=(*env)->GetByteArrayElements(env,a,NULL); if(!p)return 0; GhosttySnapshotDecoder d=NULL; GhosttyTerminal t=NULL;
    GhosttyResult r=ghostty_snapshot_decoder_new_buf(NULL,&d,(uint8_t*)p,(*env)->GetArrayLength(env,a));
    if(r==GHOSTTY_SUCCESS)r=ghostty_snapshot_decoder_decode(d,&t);
    if(d)ghostty_snapshot_decoder_free(d); (*env)->ReleaseByteArrayElements(env,a,p,JNI_ABORT);check(env,r);return wrap(env,t);
}

JNIEXPORT jbyteArray JNICALL FN(replies)(JNIEnv *env,jclass cls,jlong h) {
    AndroidTerminal *l=lab(h);
    if(l->reply_overflow){l->reply_overflow=false;l->reply_len=0;check(env,GHOSTTY_OUT_OF_SPACE);return NULL;}
    jbyteArray result=(*env)->NewByteArray(env,l->reply_len);
    if(result)(*env)->SetByteArrayRegion(env,result,0,l->reply_len,(jbyte *)l->replies);l->reply_len=0;return result;
}
JNIEXPORT jbyteArray JNICALL FN(key)(JNIEnv *env,jclass cls,jlong h,jint index) {
    GhosttyKey keys[]={GHOSTTY_KEY_ARROW_UP,GHOSTTY_KEY_ARROW_DOWN,GHOSTTY_KEY_ARROW_LEFT,GHOSTTY_KEY_ARROW_RIGHT,GHOSTTY_KEY_PAGE_UP,GHOSTTY_KEY_PAGE_DOWN,GHOSTTY_KEY_HOME,GHOSTTY_KEY_END,GHOSTTY_KEY_ENTER,GHOSTTY_KEY_TAB,GHOSTTY_KEY_ESCAPE,GHOSTTY_KEY_BACKSPACE};
    if(index<0||index>=12)return NULL;
    GhosttyKeyEncoder encoder=NULL;GhosttyKeyEvent event=NULL;
    ghostty_key_encoder_new(NULL,&encoder);ghostty_key_event_new(NULL,&event);
    ghostty_key_encoder_setopt_from_terminal(encoder,term(h));
    ghostty_key_event_set_action(event,GHOSTTY_KEY_ACTION_PRESS);ghostty_key_event_set_key(event,keys[index]);
    char buf[256];size_t n=0;GhosttyResult r=ghostty_key_encoder_encode(encoder,event,buf,sizeof(buf),&n);
    ghostty_key_event_free(event);ghostty_key_encoder_free(encoder);check(env,r);if(r!=GHOSTTY_SUCCESS)return NULL;
    jbyteArray result=(*env)->NewByteArray(env,n);if(result)(*env)->SetByteArrayRegion(env,result,0,n,(jbyte *)buf);return result;
}
JNIEXPORT jbyteArray JNICALL FN(selection)(JNIEnv *env,jclass cls,jlong h,jint start,jint end) {
    uint16_t cols=0;ghostty_terminal_get(term(h),GHOSTTY_TERMINAL_DATA_COLS,&cols);
    GhosttySelection selection=GHOSTTY_INIT_SIZED(GhosttySelection);
    selection.start=(GhosttyGridRef)GHOSTTY_INIT_SIZED(GhosttyGridRef);
    selection.end=(GhosttyGridRef)GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint a={.tag=GHOSTTY_POINT_TAG_SCREEN,.value.coordinate={.x=start%cols,.y=start/cols}};
    GhosttyPoint b={.tag=GHOSTTY_POINT_TAG_SCREEN,.value.coordinate={.x=end%cols,.y=end/cols}};
    GhosttyResult r=ghostty_terminal_grid_ref(term(h),a,&selection.start);
    if(r==GHOSTTY_SUCCESS)r=ghostty_terminal_grid_ref(term(h),b,&selection.end);
    if(r!=GHOSTTY_SUCCESS){check(env,r);return NULL;}
    GhosttyFormatterTerminalOptions options=GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
    options.emit=GHOSTTY_FORMATTER_FORMAT_PLAIN;options.unwrap=true;options.trim=true;options.selection=&selection;
    GhosttyFormatter formatter=NULL;uint8_t *data=NULL;size_t n=0;
    r=ghostty_formatter_terminal_new(NULL,&formatter,term(h),options);
    if(r==GHOSTTY_SUCCESS)r=ghostty_formatter_format_alloc(formatter,NULL,&data,&n);
    ghostty_formatter_free(formatter);check(env,r);if(r!=GHOSTTY_SUCCESS)return NULL;
    jbyteArray result=(*env)->NewByteArray(env,n);if(result)(*env)->SetByteArrayRegion(env,result,0,n,(jbyte *)data);ghostty_free(NULL,data,n);return result;
}

// Little-endian int32 stream; diagnostics keep the JSON API separately.
JNIEXPORT jbyteArray JNICALL FN(render)(JNIEnv *env,jclass cls,jlong h) {
    GhosttyTerminal t=term(h);uint16_t cols=0,rows=0,x=0,y=0;int active=0;bool mouse=false;
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_COLS,&cols);ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_CURSOR_X,&x);ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_CURSOR_Y,&y);
    ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&active);ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
    GhosttyTerminalScrollbar bar={0};ghostty_terminal_get(t,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&bar);
    ghostty_render_state_update(lab(h)->render,t);
    GhosttyRenderStateColors colors=GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);GhosttyRenderStateCursor cursor=GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(lab(h)->render,GHOSTTY_RENDER_STATE_DATA_COLORS,&colors);ghostty_render_state_get(lab(h)->render,GHOSTTY_RENDER_STATE_DATA_CURSOR,&cursor);
    size_t cap=14+(size_t)cols*rows*(7+128);int32_t *data=malloc(cap*sizeof(int32_t));
    if(!data){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/OutOfMemoryError"),"Render buffer");return NULL;}
    size_t at=0;
    int32_t header[]={cols,rows,cursor.viewport_has_value?cursor.viewport_x:0,cursor.viewport_has_value?cursor.viewport_y:0,active,mouse,bar.offset,bar.total,cursor.visible&&cursor.viewport_has_value,cursor.blinking,cursor.visual_style,colors.cursor_has_value?rgb(colors.cursor):rgb(colors.foreground),rgb(colors.background),0};
    memcpy(data,header,sizeof(header));at=14;
    for(int row=0;row<rows;row++)for(int col=0;col<cols;col++){
        GhosttyGridRef ref=GHOSTTY_INIT_SIZED(GhosttyGridRef);GhosttyPoint p={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=col,.y=row}};
        GhosttyResult r=ghostty_terminal_grid_ref(t,p,&ref);if(r!=GHOSTTY_SUCCESS){for(int i=0;i<7;i++)data[at++]=0;continue;}
        GhosttyCell cell;GhosttyCellWide wide;ghostty_grid_ref_cell(&ref,&cell);ghostty_cell_get(cell,GHOSTTY_CELL_DATA_WIDE,&wide);
        GhosttyStyle style=GHOSTTY_INIT_SIZED(GhosttyStyle);ghostty_grid_ref_style(&ref,&style);
        uint32_t cps[128];size_t n=0;r=ghostty_grid_ref_graphemes(&ref,cps,128,&n);if(r!=GHOSTTY_SUCCESS)n=0;
        int fg=color(style.fg_color,&colors,rgb(colors.foreground)),bg=color(style.bg_color,&colors,rgb(colors.background));if(style.inverse){int swap=fg;fg=bg;bg=swap;}
        data[at++]=fg;data[at++]=bg;data[at++]=wide;data[at++]=(style.bold?1:0)|(style.italic?2:0)|(style.faint?4:0)|(style.invisible?8:0)|(style.strikethrough?16:0)|(style.overline?32:0)|(style.blink?64:0);
        data[at++]=style.underline;data[at++]=color(style.underline_color,&colors,fg);data[at++]=n;
        for(size_t i=0;i<n;i++)data[at++]=cps[i];
    }
    jbyteArray result=(*env)->NewByteArray(env,at*4);if(result)(*env)->SetByteArrayRegion(env,result,0,at*4,(jbyte *)data);free(data);return result;
}
