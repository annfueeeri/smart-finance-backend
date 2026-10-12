package collector.bu.service.impl;

import collector.bu.dao.ReceiptDao;
import collector.bu.exception.ReceiptException;
import collector.bu.service.*;
import static collector.bu.model.ReceiptModels.*;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 验证文件实际格式并使用账本锁串行化上传、删除及家庭权限撤销。 */
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ReceiptServiceImpl implements ReceiptService {
    private final BookService books;
    private final ReceiptDao dao;
    /** 注入统一账本授权与收据持久化组件。 */
    public ReceiptServiceImpl(BookService books,ReceiptDao dao) { this.books=books;this.dao=dao; }
    /** 解析并锁定当前账本，流水或账户已删除时统一返回不存在。 */
    private long book(String username,long transaction) { long id=books.resolve(username).id();if(!dao.transaction(id,transaction))throw new ReceiptException("RECEIPT_NOT_FOUND");return id; }
    /** 查询当前流水附件的有效元数据。 */
    @Override public List<Receipt> list(String username,long transaction) { return dao.list(book(username,transaction),transaction); }
    /** 从文件内容识别有效图片或非加密 PDF，限制图片像素和 PDF 页数。 */
    private String type(byte[] bytes) throws IOException {
        if(bytes.length>=5 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D' && bytes[3]=='F' && bytes[4]=='-') {
            try(var pdf=Loader.loadPDF(bytes)) { if(pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>50)throw new ReceiptException("INVALID_RECEIPT");return "application/pdf"; }
        }
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new ReceiptException("INVALID_RECEIPT");var reader=readers.next();
            try { reader.setInput(input);String format=reader.getFormatName();int width=reader.getWidth(0),height=reader.getHeight(0);
                if(!(format.equalsIgnoreCase("PNG") || format.equalsIgnoreCase("JPEG")) || width<1 || height<1 || width>10000 || height>10000 || (long)width*height>20000000 || reader.read(0)==null)throw new ReceiptException("INVALID_RECEIPT");
                return format.equalsIgnoreCase("PNG") ? "image/png" : "image/jpeg";
            } finally { reader.dispose(); }
        }
    }
    /** 取原文件名的最后一段，移除控制字符，使用实际格式扩展名，不把文件名当路径。 */
    private String name(String original,String type) {
        String value=original==null ? "receipt" : original.replace('\\','/');value=value.substring(value.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "").strip();int dot=value.lastIndexOf('.');if(dot>0)value=value.substring(0,dot);if(value.isBlank() || value.equals("."))value="receipt";
        if(value.length()>150)value=value.substring(0,150);return value+(type.equals("application/pdf") ? ".pdf" : type.equals("image/png") ? ".png" : ".jpg");
    }
    /** 校验附件内容、5MB限制、每笔十份限制和活动附件重复，记录实际上传者。 */
    @Override public Receipt upload(String username,long transaction,MultipartFile file) {
        long book=book(username,transaction);
        if(file.isEmpty())throw new ReceiptException("INVALID_RECEIPT");if(file.getSize()>5242880)throw new ReceiptException("RECEIPT_TOO_LARGE");
        if(dao.list(book,transaction).size()>=10)throw new ReceiptException("RECEIPT_LIMIT");
        try {
            byte[] bytes=file.getBytes();if(bytes.length==0 || bytes.length>5242880)throw new ReceiptException("RECEIPT_TOO_LARGE");String type=type(bytes);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(dao.duplicate(book,transaction,hash))throw new ReceiptException("RECEIPT_DUPLICATE");return dao.create(book,transaction,name(file.getOriginalFilename(),type),type,bytes,hash,username);
        } catch(IOException | IllegalArgumentException e) { throw new ReceiptException("INVALID_RECEIPT"); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 返回已授权、未删除收据的二进制内容。 */
    @Override public Content content(String username,long transaction,long receipt) { return dao.content(book(username,transaction),transaction,receipt).orElseThrow(()->new ReceiptException("RECEIPT_NOT_FOUND")); }
    /** 在账本锁内逻辑删除附件，退出家庭的会话不能删除家庭收据。 */
    @Override public void delete(String username,long transaction,long receipt) { if(!dao.delete(book(username,transaction),transaction,receipt,username))throw new ReceiptException("RECEIPT_NOT_FOUND"); }
}
