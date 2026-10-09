package collector.bu.ledger;

/** 收支分类固定编码及显示名称；收入和支出分类不能互换使用。 */
public enum LedgerCategory {
    SALARY("INCOME", "給与"), BONUS("INCOME", "賞与"), PART_TIME("INCOME", "副業"),
    INVESTMENT("INCOME", "投資収益"), INTEREST("INCOME", "利息"), GIFT("INCOME", "お祝い・贈り物"),
    OTHER_INCOME("INCOME", "その他の収入"), FOOD("EXPENSE", "食費"), SHOPPING("EXPENSE", "買い物"),
    TRANSPORT("EXPENSE", "交通費"), HOUSING("EXPENSE", "住居"), ENTERTAINMENT("EXPENSE", "娯楽"),
    MEDICAL("EXPENSE", "医療費"), OTHER_EXPENSE("EXPENSE", "その他の支出");
    private final String kind;
    private final String label;
    /** 为分类绑定对应收支方向及显示名称。 */
    LedgerCategory(String kind, String label) { this.kind = kind; this.label = label; }
    /** 返回分类所属的收入或支出方向。 */
    public String kind() { return kind; }
    /** 返回页面显示的分类名称。 */
    public String label() { return label; }
}
