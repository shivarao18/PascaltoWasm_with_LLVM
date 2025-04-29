program TestClassWithFunctionAndProcedure;

type
  MyClass = class;
  public
    X: Integer;
    procedure SetX(Value: Integer);
    function GetX: Integer;
  end;

procedure MyClass.SetX(Value: Integer);
begin
  X := Value;
end;

function MyClass.GetX: Integer;
begin
  GetX := X;
end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  Obj.SetX(27);
  WRITELN(Obj.GetX);
  WRITELN(Obj.X);
end.