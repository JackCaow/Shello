package dev.ghostty.android.sample;

import java.util.Locale;

/** File-provider MIME types are often generic (or ZIP for Office files), so use known suffixes first. */
enum AttachmentFileType {
    WORD("file_word",0xff83b1ff,0xff14243d,R.string.attachment_type_word),
    PRESENTATION("file_presentation",0xffffac83,0xff342016,R.string.attachment_type_presentation),
    SPREADSHEET("file_spreadsheet",0xff78d8a5,0xff142d22,R.string.attachment_type_spreadsheet),
    PDF("file_pdf",0xffff9398,0xff341b20,R.string.attachment_type_pdf),
    AUDIO("file_audio",0xffc2a1ff,0xff271c39,R.string.attachment_type_audio),
    ARCHIVE("file_archive",0xffedcd7c,0xff302816,R.string.attachment_type_archive),
    CODE("file_code",0xff7bd7e9,0xff132b32,R.string.attachment_type_code),
    TEXT("file",0xffb9c4cf,0xff23282d,R.string.attachment_type_text),
    IMAGE("image",0xffc6a6e5,0xff2b2033,R.string.attachment_type_image),
    VIDEO("file_video",0xfff4a5c7,0xff331f2b,R.string.attachment_type_video),
    OTHER("file_unknown",SampleUi.MUTED,0xff171a1c,R.string.attachment_type_other);

    final String icon;final int foreground,background,label;
    AttachmentFileType(String icon,int foreground,int background,int label){this.icon=icon;this.foreground=foreground;this.background=background;this.label=label;}
    static AttachmentFileType resolve(String name,String mime){
        String filename=name==null?"":name.trim().toLowerCase(Locale.ROOT);
        int dot=filename.lastIndexOf('.');String suffix=dot<0?"":filename.substring(dot+1);
        AttachmentFileType known=switch(suffix){
            case "doc","docx","docm","dot","dotx","dotm","odt","ott","rtf","pages"->WORD;
            case "ppt","pptx","pptm","pps","ppsx","ppsm","pot","potx","potm","odp","otp","key"->PRESENTATION;
            case "xls","xlsx","xlsm","xlsb","xlt","xltx","xltm","ods","ots","csv","tsv","numbers"->SPREADSHEET;
            case "pdf"->PDF;
            case "mp3","wav","m4a","aac","flac","ogg","opus","aif","aiff","amr","wma","mid","midi","weba"->AUDIO;
            case "zip","7z","rar","tar","gz","bz2","xz","tgz","tbz2","txz","zst","lz4"->ARCHIVE;
            case "js","jsx","ts","tsx","kt","kts","java","c","cc","cpp","h","hpp","cs","go","rs","py","rb","php","swift","sh","bash","zsh","fish","ps1","json","jsonc","yaml","yml","toml","xml","html","htm","css","scss","sql","gradle","vue","svelte","ipynb","dart","lua","env","gitignore"->CODE;
            case "txt","log","md","markdown","rst","adoc","tex"->TEXT;
            case "png","jpg","jpeg","webp","gif","heic","heif","avif","bmp","tif","tiff","ico","svg"->IMAGE;
            case "mp4","mov","mkv","webm","avi","wmv","flv","m4v","mpeg","mpg","3gp"->VIDEO;
            default->null;
        };
        if(known!=null)return known;
        if(filename.equals("dockerfile")||filename.equals("makefile")||filename.equals("gemfile"))return CODE;
        String type=mime==null?"":mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(type.startsWith("audio/"))return AUDIO;if(type.startsWith("image/"))return IMAGE;if(type.startsWith("video/"))return VIDEO;
        return switch(type){
            case "application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.wordprocessingml.template","application/vnd.ms-word.document.macroenabled.12","application/vnd.ms-word.template.macroenabled.12","application/vnd.oasis.opendocument.text","application/rtf","text/rtf","application/vnd.apple.pages"->WORD;
            case "application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.presentationml.slideshow","application/vnd.openxmlformats-officedocument.presentationml.template","application/vnd.ms-powerpoint.presentation.macroenabled.12","application/vnd.ms-powerpoint.slideshow.macroenabled.12","application/vnd.ms-powerpoint.template.macroenabled.12","application/vnd.oasis.opendocument.presentation","application/vnd.apple.keynote"->PRESENTATION;
            case "application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.spreadsheetml.template","application/vnd.ms-excel.sheet.macroenabled.12","application/vnd.ms-excel.sheet.binary.macroenabled.12","application/vnd.ms-excel.template.macroenabled.12","application/vnd.oasis.opendocument.spreadsheet","text/csv","text/tab-separated-values","application/vnd.apple.numbers"->SPREADSHEET;
            case "application/pdf"->PDF;
            case "application/zip","application/x-zip-compressed","application/x-7z-compressed","application/vnd.rar","application/x-rar-compressed","application/x-tar","application/gzip","application/x-gzip","application/x-bzip2","application/x-xz","application/zstd"->ARCHIVE;
            case "application/json","application/ld+json","application/xml","text/xml","application/javascript","text/javascript","application/x-sh","application/yaml","text/yaml","text/html","text/css","text/x-python","text/x-java-source","text/x-c","text/x-c++"->CODE;
            default->type.startsWith("text/")?TEXT:OTHER;
        };
    }
}
