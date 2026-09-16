package hu.baader.repl.fixture;

@jakarta.persistence.Entity
public class EnhancedDocument {
    @jakarta.persistence.Id public int id;
    @jakarta.persistence.Basic(fetch=jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.Lob public String text;
    public EnhancedDocument() {}
    public String getText(){return text;}
}
