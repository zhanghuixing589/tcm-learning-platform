package cn.tcm.platform.account.application;

import cn.tcm.platform.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class AvatarService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AvatarService.class);
    private static final long MAX_BYTES=2*1024*1024;
    private static final String OWNED_FILE="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png";
    private final JdbcTemplate db;
    private final Path root;
    public AvatarService(JdbcTemplate db,@Value("${tcm.storage.avatar-directory:${java.io.tmpdir}/tcm-platform-avatars}") String directory) {this.db=db;this.root=Path.of(directory).toAbsolutePath().normalize();}
    @Transactional public Map<String,String> upload(long user,MultipartFile file) {
        if(file.isEmpty()||file.getSize()>MAX_BYTES)throw ApiException.invalid("头像须为2MB以内的PNG或JPEG图片");
        Path target=null;boolean fileCreated=false,cleanupScheduled=false;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw ApiException.invalid("无法识别图片");
            var reader=readers.next();BufferedImage decoded;
            try {
                if(!Set.of("png","jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw ApiException.invalid("仅支持PNG或JPEG图片");
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>2048||height>2048||(long)width*height>4_000_000)throw ApiException.invalid("图片尺寸最大2048像素，像素总量最多400万");
                decoded=reader.read(0);
                if(decoded==null)throw ApiException.invalid("图片解码失败");
            } finally {reader.dispose();}
            // Re-encode decoded pixels: filenames, supplied MIME, original metadata and embedded payloads are never persisted.
            var safe=new BufferedImage(decoded.getWidth(),decoded.getHeight(),BufferedImage.TYPE_INT_ARGB);
            var graphics=safe.createGraphics();try {graphics.drawImage(decoded,0,0,null);} finally {graphics.dispose();}
            // Lock before reading the previous key or creating a replacement. Concurrent uploads for this user serialize.
            var users=db.queryForList("SELECT avatar_path FROM sys_users WHERE id=? AND status='ACTIVE' FOR UPDATE",user);
            if(users.isEmpty())throw ApiException.missing();
            String previous=(String)users.get(0).get("avatar_path");
            Files.createDirectories(root);String key=UUID.randomUUID()+".png";target=root.resolve(key);
            var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW);fileCreated=true;
            try(output){if(!ImageIO.write(safe,"png",output))throw new IOException("PNG encoder unavailable");}
            Path replacement=target;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if(status==STATUS_COMMITTED)removePrevious(previous,user);
                    else if(status==STATUS_ROLLED_BACK)removeOwned(replacement);
                    else LOG.warn("Avatar transaction outcome unknown for user={}",user);
                }
            });
            cleanupScheduled=true;
            if(db.update("UPDATE sys_users SET avatar_path=? WHERE id=? AND status='ACTIVE'",key,user)!=1)throw ApiException.missing();
            return Map.of("avatarUrl","/api/v1/users/"+user+"/avatar");
        } catch(IOException|IllegalArgumentException e) {
            if(fileCreated&&!cleanupScheduled)removeOwned(target);
            throw ApiException.invalid("图片无法读取，请使用完整的PNG或JPEG图片");
        } catch(RuntimeException e) {
            if(fileCreated&&!cleanupScheduled)removeOwned(target);
            throw e;
        }
    }
    private Path ownedPath(String key) {
        if(key==null||!key.matches(OWNED_FILE))return null;
        Path target=root.resolve(key).normalize();return root.equals(target.getParent())?target:null;
    }
    private void removeOwned(Path target) {
        if(target==null||!root.equals(target.getParent())||!target.getFileName().toString().matches(OWNED_FILE))return;
        try {Files.deleteIfExists(target);}catch(IOException e){LOG.warn("Could not remove obsolete avatar file");}
    }
    private void removePrevious(String key,long user) {
        Path previous=ownedPath(key);if(previous==null)return;
        try {
            // A legacy/shared key must never invalidate another account's current avatar.
            if(Objects.equals(db.queryForObject("SELECT COUNT(*) FROM sys_users WHERE avatar_path=?",Long.class,key),0L))removeOwned(previous);
        } catch(RuntimeException e) {LOG.warn("Could not check previous avatar ownership for user={}",user);}
    }
    public byte[] read(long user) {
        var paths=db.queryForList("SELECT avatar_path FROM sys_users WHERE id=? AND status='ACTIVE' AND avatar_path IS NOT NULL",String.class,user);
        if(paths.isEmpty())throw ApiException.missing();
        Path target=ownedPath(paths.get(0));if(target==null)throw ApiException.missing();
        try {return Files.readAllBytes(target);}catch(IOException e){throw ApiException.missing();}
    }
}
