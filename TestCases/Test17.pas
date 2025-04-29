program TestWhileInsideClassMethod;

type
  MyClass = class;
  public
    procedure PrintWhile;
  end;

procedure MyClass.PrintWhile;
var
  I: Integer;
begin
  I := 0;
  while I < 10 do
  begin
    I := I + 1;
    WRITELN(I);
  end;
end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  Obj.PrintWhile;
end.