program TestForInsideClassMethod;

type
  MyClass = class;
  public
    procedure PrintFor;
  end;

procedure MyClass.PrintFor;
var
  I: Integer;
begin
  for I := 1 to 10 do
  begin
    if I = 2 then
    begin
      continue;
    end;
    if I = 8 then
    begin
      break;
    end;
    WRITELN(I);
  end;
end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  Obj.PrintFor;
end.