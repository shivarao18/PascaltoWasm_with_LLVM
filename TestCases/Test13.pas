program TestConstructor;

type
  MyClass = class;
  public
    X: Integer;
    constructor Create;
  end;

constructor MyClass.Create;
begin
  X := 27;
end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  WRITELN(Obj.X);
end.