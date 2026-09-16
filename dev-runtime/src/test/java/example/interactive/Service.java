package example.interactive;
import java.util.List;
public class Service implements Calculator {
    public record Input(int amount,List<String> tags) {}
    public static int created,calls,factor=1;
    public Service(){created++;}
    public int calculate(Input input,int multiplier){calls++;return input.amount()*multiplier;}
    public String calculate(String input){return input;}
    public void fail(Input input){throw new IllegalArgumentException("bad input");}
}
